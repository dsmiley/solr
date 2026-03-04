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
import static org.apache.solr.bench.BaseBenchState.log;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.embedded.JettySolrRunner;

/** {@link SolrBenchBackend} implementation backed by an embedded {@link MiniSolrCloudCluster}. */
public class MiniClusterBackend implements SolrBenchBackend {

  private final Path miniClusterBaseDir;

  protected MiniSolrCloudCluster cluster;
  private SolrClient client;
  private List<String> nodes;

  public MiniClusterBackend(Path miniClusterBaseDir) {
    this.miniClusterBaseDir = miniClusterBaseDir;
  }

  @Override
  public void start(int nodeCount) throws Exception {
    System.setProperty("doNotWaitForMergesOnIWClose", "true");
    System.setProperty("pkiHandlerPrivateKeyPath", "");
    System.setProperty("pkiHandlerPublicKeyPath", "");
    System.setProperty("solr.configset.default.confdir", "../server/solr/configsets/_default");

    log("starting mini cluster at base directory: " + miniClusterBaseDir.toAbsolutePath());

    cluster =
        new MiniSolrCloudCluster.Builder(nodeCount, miniClusterBaseDir)
            .formatZkServer(false)
            .build();

    nodes = new ArrayList<>(nodeCount);
    List<JettySolrRunner> jetties = cluster.getJettySolrRunners();
    for (JettySolrRunner runner : jetties) {
      nodes.add(runner.getBaseUrl().toString());
    }

    log("done starting mini cluster");
    log("");
  }

  @Override
  public SolrClient getClient(String collection) {
    // never cache null (admin request)
    if (collection == null) {
      return createClient(null);
    }
    if (client == null) {
      client = createClient(collection);
    }
    return client;
  }

  /** Returns the ZooKeeper address */
  public String getZkHost() {
    return cluster.getZkServer().getZkAddress();
  }

  /** Returns the list of Jetty node base URLs. */
  public List<String> getNodes() {
    return nodes;
  }

  @Override
  public void registerConfigset(Path configDir) throws Exception {
    String configSetName = configDir.getFileName().toString();
    List<String> existing =
        new ConfigSetAdminRequest.List().process(cluster.getSolrClient()).getConfigSets();
    if (existing == null || !existing.contains(configSetName)) {
      cluster.uploadConfigSet(configDir.resolve("conf"), configSetName);
    }
  }

  @Override
  public boolean createCollection(
      String name, String configName, int shards, int replicas, Map<String, String> properties)
      throws Exception {
    SolrClient c = getClient(name);
    CollectionAdminResponse listResponse = new CollectionAdminRequest.List().process(c);
    @SuppressWarnings("unchecked")
    List<String> existing = (List<String>) listResponse.getResponse().get("collections");
    if (existing != null && existing.contains(name)) {
      return false;
    }
    try {
      CollectionAdminRequest.Create create =
          CollectionAdminRequest.createCollection(name, configName, shards, replicas);
      if (!properties.isEmpty()) {
        create.setProperties(properties);
      }
      client.request(create, null);
      cluster.waitForActiveCollection(name, 15, TimeUnit.SECONDS, shards, shards * replicas);
    } catch (Exception e) {
      if (Files.exists(miniClusterBaseDir)) {
        deleteDirectory(miniClusterBaseDir);
      }
      throw e;
    }
    return true;
  }

  protected SolrClient createClient(String collection) {
    if (nodes.size() != 1) {
      return new CloudSolrClient.Builder(cluster.getSolrClient().getClusterStateProvider())
          .withDefaultCollection(collection)
          .build();
    }
    return new HttpJettySolrClient.Builder(nodes.getFirst())
        .withDefaultCollection(collection)
        .build();
  }

  @Override
  public void dumpMetrics(PrintStream out) throws Exception {
    cluster.dumpMetrics(out);
  }

  @Override
  public void dumpCoreInfo(PrintStream out) throws Exception {
    cluster.dumpCoreInfo(out);
  }

  @Override
  public void close() throws Exception {
    IOUtils.closeQuietly(client);
    if (cluster != null) {
      cluster.shutdown();
    }
    logClusterDirectorySize();
  }

  private void logClusterDirectorySize() {
    log("");
    if (!Files.exists(miniClusterBaseDir)) {
      return;
    }
    try {
      Files.list(miniClusterBaseDir.toAbsolutePath())
          .forEach(
              node -> {
                try {
                  long clusterSize =
                      Files.walk(node)
                          .filter(Files::isRegularFile)
                          .mapToLong(
                              file -> {
                                try {
                                  return Files.size(file);
                                } catch (IOException e) {
                                  throw new RuntimeException(e);
                                }
                              })
                          .sum();
                  log("mini cluster node size (bytes) " + node + " " + clusterSize);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (IOException e) {
      log("unable to log cluster directory size: " + e.getMessage());
    }
  }
}
