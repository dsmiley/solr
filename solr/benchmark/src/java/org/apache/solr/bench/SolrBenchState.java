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

import static org.apache.solr.bench.BaseBenchState.log;

import com.codahale.metrics.Meter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.util.SolrTestNonSecureRandomProvider;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Control;

/**
 * JMH {@link State} class injected into all benchmarks. Selects a {@link SolrBenchBackend} at
 * runtime via the {@code solr.bench.backend} system property ({@code minicluster} by default).
 *
 * <p>The collection name defaults to the simple class name of the benchmark (e.g. {@code
 * SimpleSearch}). Call {@link #setCollection(String)} before {@link #start(int, int, int)} to
 * override.
 */
@State(Scope.Benchmark)
public class SolrBenchState {

  private SolrBenchBackend backend;

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
  private String workDir;

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

    int nodeCount = Integer.getInteger("solr.bench.nodeCount", defaultNodeCount);
    numShards = Integer.getInteger("solr.bench.numShards", defaultNumShards);
    numReplicas = Integer.getInteger("solr.bench.numReplicas", defaultNumReplicas);

    String backendType = System.getProperty("solr.bench.backend", "minicluster");

    if ("minicluster".equals(backendType)) {
      Path indexDir;
      boolean allowClusterReuse;
      String indexDirProp = System.getProperty("solr.bench.index.dir");
      if (indexDirProp != null) {
        indexDir = Path.of(indexDirProp);
        allowClusterReuse = Files.exists(indexDir);
      } else {
        indexDir = Path.of(workDir, "mini-cluster");
        allowClusterReuse = false;
      }
      backend = new MiniClusterBackend(indexDir, allowClusterReuse);
    } else if ("remote".equals(backendType)) {
      backend = new RemoteSolrBackend();
    } else {
      throw new IllegalArgumentException("Unknown solr.bench.backend: " + backendType);
    }

    backend.start(nodeCount);

    registerConfigset(getFile("src/resources/configs/cloud-minimal"));
  }

  /** Returns the underlying backend; useful for backend-specific features (e.g. ZK host). */
  public SolrBenchBackend getBackend() {
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
    backend.registerConfigset(configDir);
  }

  /**
   * Creates the collection if it does not already exist.
   *
   * @return {@code true} if the collection was just created and the benchmark should load data;
   *     {@code false} if it already existed and can be used as-is.
   */
  public boolean createCollection(String configName, Map<String, String> properties)
      throws Exception {
    boolean created =
        backend.createCollection(collection, configName, numShards, numReplicas, properties);
    benchmarkClient = backend.getClient(collection);
    return created;
  }

  public void reloadCollection() throws Exception {
    backend.reloadCollection(collection);
  }

  public void forceMerge(int maxSegments) throws Exception {
    backend.forceMerge(collection, maxSegments);
  }

  public void waitForMerges() throws Exception {
    backend.waitForMerges(collection);
  }

  public SplittableRandom getRandom() {
    return random;
  }

  @SuppressForbidden(reason = "This module does not need to deal with logging context")
  public void index(Docs docs, int docCount) throws Exception {
    index(docs, docCount, true);
  }

  @SuppressForbidden(reason = "This module does not need to deal with logging context")
  public void index(Docs docs, int docCount, boolean parallel) throws Exception {
    if (!Boolean.getBoolean("solr.bench.skipIndexing")) {
      log("indexing data for benchmark...");
      if (parallel) {
        indexParallel(docs, docCount);
      } else {
        indexBatch(docs, docCount, 10000);
      }
      log("done indexing data for benchmark");

      log("committing data ...");
      UpdateRequest commitRequest = new UpdateRequest();
      commitRequest.setAction(UpdateRequest.ACTION.COMMIT, false, true);
      benchmarkClient.request(commitRequest, collection);
      log("done committing data");
    }

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
    backend.dumpCoreInfo();
  }

  @Setup(Level.Iteration)
  public void checkWarmUp(Control control) throws Exception {
    isWarmup = control.stopMeasurement;
  }

  @TearDown(Level.Iteration)
  public void tearDown(BenchmarkParams benchmarkParams) throws Exception {
    if (backend == null) return;
    Path metricsResults =
        Path.of(
            workDir,
            "metrics-results",
            benchmarkParams.id(),
            String.valueOf(runCnt),
            benchmarkParams.getBenchmark() + ".txt");
    runCnt++;
    Files.createDirectories(metricsResults.getParent());
    backend.dumpMetrics(metricsResults);
  }

  @TearDown(Level.Trial)
  public void shutdown(BenchmarkParams benchmarkParams, BaseBenchState baseBenchState)
      throws Exception {
    BaseBenchState.dumpHeap(benchmarkParams);
    try {
      if (benchmarkClient != null) {
        benchmarkClient.close();
      }
    } finally {
      if (backend != null) {
        backend.close();
      }
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
}
