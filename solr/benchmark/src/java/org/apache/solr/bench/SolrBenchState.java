/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.bench;

import static org.apache.commons.io.file.PathUtils.deleteDirectory;
import static org.apache.commons.io.file.PathUtils.sizeOfDirectory;
import static org.apache.solr.bench.BaseBenchState.log;

import com.codahale.metrics.Meter;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.api.model.CreateCollectionRequestBody;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.util.EmbeddedSolrBackend;
import org.apache.solr.util.RemoteSolrBackend;
import org.apache.solr.util.SolrBackend;
import org.apache.solr.util.SolrTestNonSecureRandomProvider;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Control;

/**
 * JMH {@link State} class injected into all benchmarks. Selects a {@link SolrBackend} at runtime
 * via the {@code solr.bench.backend} system property ({@code minicluster} by default).
 *
 * <p>The collection name defaults to the simple class name of the benchmark (e.g. {@code
 * SimpleSearch}). Call {@link #setCollection(String)} before {@link #start(int, int, int)} to
 * override.
 */
@State(Scope.Benchmark)
public class SolrBenchState {

  private SolrBackend backend;

  /**
   * Client whose base URL already includes the collection path; returned by {@link #getClient()} so
   * benchmark code can call {@code request(req)} / {@code process(client)} without a collection
   * argument.
   */
  private SolrClient benchmarkClient;

  /** The Solr collection used by this benchmark instance. */
  private String collection;

  private int numShards;
  private int numReplicas;

  int runCnt = 0;
  boolean metricsEnabled = true;
  boolean isWarmup;

  private SplittableRandom random;
  private Path workDir;
  private Path indexDir;

  @Setup(Level.Trial)
  public void doSetup(BenchmarkParams benchmarkParams, BaseBenchState baseBenchState)
      throws Exception {
    if (!Boolean.getBoolean("doNotWeakenSecureRandom")) {
      SolrTestNonSecureRandomProvider.injectProvider();
    }

    workDir = baseBenchState.workDir;
    this.random = new SplittableRandom(BaseBenchState.getRandomSeed());
    System.setProperty("metricsEnabled", String.valueOf(metricsEnabled));

    // Collection name: system property override, or default to simple class name of the benchmark.
    // BenchmarkParams.getBenchmark() returns "pkg.ClassName.methodName"
    String collectionSysProp = System.getProperty("solr.bench.collection");
    if (collectionSysProp != null && !collectionSysProp.isBlank()) {
      this.collection = collectionSysProp;
    } else {
      String fqBenchmark = benchmarkParams.getBenchmark();
      String fqClass = fqBenchmark.substring(0, fqBenchmark.lastIndexOf('.'));
      this.collection = fqClass.substring(fqClass.lastIndexOf('.') + 1);
    }
  }

  /**
   * Override the collection name. Must be called before {@link #start(int, int, int)} if a custom
   * name is needed.
   */
  public void setCollection(String collection) {
    this.collection = collection;
  }

  /** Returns the collection name used by this benchmark instance. */
  public String getCollection() {
    return collection;
  }

  /**
   * Creates and starts the backend. The three arguments are <em>default</em> values; they can be
   * overridden at runtime via the {@code solr.bench.nodeCount}, {@code solr.bench.numShards}, and
   * {@code solr.bench.numReplicas} system properties. The resolved shard/replica counts are stored
   * and used by {@link #createCollection(String, Map)}.
   *
   * <p>Called explicitly by each benchmark's own {@code @Setup(Level.Trial)} method.
   */
  public void start(int defaultNodeCount, int defaultNumShards, int defaultNumReplicas)
      throws Exception {
    start(defaultNodeCount, defaultNumShards, defaultNumReplicas, SolrBenchBackendType.MINICLUSTER);
  }

  public void start(
      int defaultNodeCount,
      int defaultNumShards,
      int defaultNumReplicas,
      SolrBenchBackendType defaultBackend)
      throws Exception {

    int nodeCount = Integer.getInteger("solr.bench.nodeCount", defaultNodeCount);
    numShards = Integer.getInteger("solr.bench.numShards", defaultNumShards);
    numReplicas = Integer.getInteger("solr.bench.numReplicas", defaultNumReplicas); // nocommit

    String backendProp = System.getProperty("solr.bench.backend");
    SolrBenchBackendType backendType =
        backendProp != null
            ? SolrBenchBackendType.valueOf(backendProp.toUpperCase(Locale.ROOT))
            : defaultBackend;

    String indexDirProp = System.getProperty("solr.bench.index.dir");
    Path indexDir =
        indexDirProp != null
            ? Path.of(indexDirProp)
            : workDir.resolve(backendType.name()).resolve(collection);

    boolean dirExisted = Files.exists(indexDir);
    if (dirExisted) {
      log("index dir exists, reusing: " + indexDir.toAbsolutePath());
    }

    this.indexDir = indexDir;

    try {
      switch (backendType) {
        case MINICLUSTER -> {
          System.setProperty("pkiHandlerPrivateKeyPath", "");
          System.setProperty("pkiHandlerPublicKeyPath", "");
          System.setProperty(
              "solr.configset.default.confdir", "../server/solr/configsets/_default");
          log("starting mini cluster at base directory: " + indexDir.toAbsolutePath());
          MiniSolrCloudCluster cluster =
              new MiniSolrCloudCluster.Builder(nodeCount, indexDir).formatZkServer(false).build();
          // Wait for any pre-existing collections to become active
          var clusterState = cluster.getZkStateReader().getClusterState();
          for (String collectionName : clusterState.getCollectionNames()) {
            cluster.waitForActiveCollection(collectionName, 30, TimeUnit.SECONDS);
          }
          backend = cluster;
        }
        case EMBEDDED -> {
          var embedded = new EmbeddedSolrBackend(indexDir);
          embedded.start();
          backend = embedded;
        }
        case REMOTE -> {
          String url = System.getProperty("solr.bench.url");
          if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "solr.bench.backend=remote requires -Dsolr.bench.url=http://host:port/solr");
          }
          backend = new RemoteSolrBackend(url);
        }
      }
    } catch (Exception e) {
      if (!dirExisted && Files.exists(indexDir)) {
        try {
          deleteDirectory(indexDir);
        } catch (IOException ex) {
          e.addSuppressed(ex);
        }
      }
      throw e;
    }
    log("done starting " + backendType);
    log("");

    try {
      backend.registerConfigset(getFile("src/resources/configs/cloud-minimal"));
    } catch (SolrBackend.AlreadyExistsException e) {
      // configset already registered from a prior run; reuse it
    }
  }

  /** Returns the underlying backend; useful for backend-specific features (e.g. ZK host). */
  public SolrBackend getBackend() {
    return backend;
  }

  /**
   * Returns a {@link SolrClient} defaulted to the collection, so callers can use the
   * no-collection-param overloads of {@code request()} and {@code process()}.
   */
  public SolrClient getClient() {
    return benchmarkClient;
  }

  public void registerConfigset(Path configDir) throws Exception {
    try {
      backend.registerConfigset(configDir);
    } catch (SolrBackend.AlreadyExistsException e) {
      // configset already registered from a prior run; reuse it
    }
  }

  /**
   * Creates the collection if it does not already exist.
   *
   * @return {@code true} if the collection was just created and the benchmark should load data;
   *     {@code false} if it already existed and can be used as-is.
   */
  public boolean createCollection(String configName, Map<String, String> properties)
      throws Exception {
    boolean created;
    try {
      var body = new CreateCollectionRequestBody();
      body.name = collection;
      body.config = configName;
      body.numShards = numShards;
      body.replicationFactor = numReplicas;
      if (!properties.isEmpty()) {
        body.properties = properties;
      }
      backend.createCollection(body);
      created = true;
    } catch (SolrBackend.AlreadyExistsException e) {
      created = false;
    }
    benchmarkClient = backend.newClient(collection);
    if (!created) {
      log("Using EXISTING collection: " + collection);
    }
    return created;
  }

  public void reloadCollection() throws Exception {
    backend.reloadCollection(collection);
  }

  public void forceMerge(int maxSegments) throws Exception {
    log("merging segments to " + maxSegments + " segments ...\n");
    new UpdateRequest()
        .setAction(UpdateRequest.ACTION.OPTIMIZE, false, true, maxSegments)
        .process(benchmarkClient);
  }

  public void waitForMerges() throws Exception {
    log("waiting for merges to finish...\n");
    new UpdateRequest()
        .setAction(UpdateRequest.ACTION.OPTIMIZE, false, true, Integer.MAX_VALUE)
        .process(benchmarkClient);
  }

  public SplittableRandom getRandom() {
    return random;
  }

  public void index(Docs docs, int docCount) throws Exception {
    index(docs, docCount, true);
  }

  public void index(Docs docs, int docCount, boolean parallel) throws Exception {
    log("indexing data for benchmark...");
    if (parallel) {
      indexParallel(docs, docCount);
    } else {
      indexBatch(docs, docCount, 10_000);
    }
    log("done indexing data for benchmark");

    log("committing data ...");
    benchmarkClient.commit(collection);
    log("done committing data");

    QueryRequest queryRequest = new QueryRequest(new SolrQuery("q", "*:*", "rows", "1"));
    NamedList<Object> result = benchmarkClient.request(queryRequest, collection);
    log("sanity check of single row query result: " + result);
    log("");

    log("Dump Core Info");
    dumpCoreInfo();
  }

  @SuppressForbidden(reason = "This module does not need to deal with logging context")
  private void indexParallel(Docs docs, int docCount) throws InterruptedException {
    Meter meter = new Meter();
    ExecutorService executorService =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            new SolrNamedThreadFactory("SolrJMH Indexer"));
    ScheduledExecutorService scheduledExecutor =
        Executors.newSingleThreadScheduledExecutor(
            new SolrNamedThreadFactory("SolrJMH Indexer Progress"));
    scheduledExecutor.scheduleAtFixedRate(
        () -> {
          if (meter.getCount() == docCount) {
            scheduledExecutor.shutdown();
          } else {
            log(meter.getCount() + " docs at " + meter.getMeanRate() + " doc/s");
          }
        },
        10,
        10,
        TimeUnit.SECONDS);
    for (int i = 0; i < docCount; i++) {
      executorService.execute(
          new Runnable() {
            final SplittableRandom threadRandom = random.split();

            @Override
            public void run() {
              UpdateRequest updateRequest = new UpdateRequest();
              SolrInputDocument doc = docs.inputDocument();
              updateRequest.add(doc);
              meter.mark();
              try {
                benchmarkClient.request(updateRequest, collection);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          });
    }

    log("done adding docs, waiting for executor to terminate...");
    executorService.shutdown();
    boolean result = false;
    while (!result) {
      result = executorService.awaitTermination(600, TimeUnit.MINUTES);
    }
    scheduledExecutor.shutdown();
  }

  private void indexBatch(Docs docs, int docCount, int batchSize)
      throws SolrServerException, IOException {
    Meter meter = new Meter();
    List<SolrInputDocument> batch = new ArrayList<>(batchSize);
    for (int i = 1; i <= docCount; i++) {
      batch.add(docs.inputDocument());
      if (i % batchSize == 0) {
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.add(batch);
        benchmarkClient.request(updateRequest, collection);
        meter.mark(batch.size());
        batch.clear();
        log(meter.getCount() + " docs at " + (long) meter.getMeanRate() + " doc/s");
      }
    }
    if (!batch.isEmpty()) {
      UpdateRequest updateRequest = new UpdateRequest();
      updateRequest.add(batch);
      benchmarkClient.request(updateRequest, collection);
      meter.mark(batch.size());
    }
    log(meter.getCount() + " docs at " + (long) meter.getMeanRate() + " doc/s");
  }

  public void dumpCoreInfo() throws Exception {
    if (BaseBenchState.QUIET_LOG) {
      return;
    }
    backend.dumpCoreInfo(System.out);
  }

  @Setup(Level.Iteration)
  public void checkWarmUp(Control control) throws Exception {
    isWarmup = control.stopMeasurement;
  }

  @TearDown(Level.Iteration)
  public void tearDown(BenchmarkParams benchmarkParams) throws Exception {
    if (backend == null) return;
    Path metricsResults =
        workDir
            .resolve("metrics-results")
            .resolve(benchmarkParams.id())
            .resolve(String.valueOf(runCnt))
            .resolve(benchmarkParams.getBenchmark() + ".txt");
    runCnt++;
    Files.createDirectories(metricsResults.getParent());
    try (var out = new PrintStream(Files.newOutputStream(metricsResults))) {
      backend.dumpMetrics(out);
    }
  }

  @TearDown(Level.Trial)
  public void shutdown(BenchmarkParams benchmarkParams, BaseBenchState baseBenchState)
      throws Exception {
    BaseBenchState.dumpHeap(benchmarkParams);
    IOUtils.closeQuietly(benchmarkClient);
    if (backend != null) {
      backend.close();
    }

    logIndexDirSize(indexDir, backend instanceof MiniSolrCloudCluster);

    String orr = ObjectReleaseTracker.clearObjectTrackerAndCheckEmpty();
    if (orr != null) {
      throw new AssertionError("ObjectReleaseTracker found unreleased objects:\n" + orr);
    }
  }

  /**
   * Creates a {@link ModifiableSolrParams} from key-value pairs.
   *
   * @param moreParams alternating key, value strings
   */
  public static ModifiableSolrParams params(String... moreParams) {
    ModifiableSolrParams params = new ModifiableSolrParams();
    for (int i = 0; i < moreParams.length; i += 2) {
      params.add(moreParams[i], moreParams[i + 1]);
    }
    return params;
  }

  /**
   * Adds key-value pairs to an existing {@link ModifiableSolrParams}.
   *
   * @param params base params to extend
   * @param moreParams alternating key, value strings
   */
  public static ModifiableSolrParams params(ModifiableSolrParams params, String... moreParams) {
    for (int i = 0; i < moreParams.length; i += 2) {
      params.add(moreParams[i], moreParams[i + 1]);
    }
    return params;
  }

  /**
   * Resolves a resource path from the classpath or the local filesystem (relative to CWD). Use this
   * to locate configset directories bundled with the benchmark module.
   */
  public static Path getFile(String name) {
    final URL url =
        SolrBenchState.class
            .getClassLoader()
            .getResource(name.replace(FileSystems.getDefault().getSeparator(), "/"));
    if (url != null) {
      try {
        return Path.of(url.toURI());
      } catch (Exception e) {
        throw new RuntimeException(
            "Resource was found on classpath, but cannot be resolved to a "
                + "normal file (maybe it is part of a JAR file): "
                + name);
      }
    }
    Path file = Path.of(name);
    if (Files.exists(file)) {
      return file;
    }
    file = Path.of("../../../", name);
    if (Files.exists(file)) {
      return file;
    }
    throw new RuntimeException(
        "Cannot find resource in classpath or in file-system (relative to CWD): "
            + name
            + " CWD="
            + Path.of("").toAbsolutePath());
  }

  /** Logs the total size and first-level child sizes of the index directory. */
  private static void logIndexDirSize(Path indexDir, boolean logFirstLevel) {
    if (indexDir == null || !Files.exists(indexDir)) {
      return;
    }

    log("");
    try {
      long totalSize = sizeOfDirectory(indexDir);
      log("index directory total size (bytes): " + totalSize);

      if (!logFirstLevel) {
        return;
      }
      // Log first-level child sizes
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(indexDir)) {
        for (Path child : stream) {
          try {
            long childSize = Files.isDirectory(child) ? sizeOfDirectory(child) : Files.size(child);
            log("index directory child size (bytes) " + child.getFileName() + ": " + childSize);
          } catch (IOException e) {
            log("unable to measure size of " + child.getFileName() + ": " + e.getMessage());
          }
        }
      }
    } catch (IOException e) {
      log("unable to log index directory size: " + e.getMessage());
    }
  }
}
