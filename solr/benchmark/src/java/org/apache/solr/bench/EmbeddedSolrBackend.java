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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.core.CoreContainer;

/**
 * {@link SolrBenchBackend} implementation backed by an in-process {@link EmbeddedSolrServer}. No
 * network or ZooKeeper overhead; suitable for benchmarks that are not measuring cloud or HTTP
 * behaviour.
 *
 * <p>Shard and replica counts are ignored — {@link EmbeddedSolrServer} is single-core.
 */
public class EmbeddedSolrBackend implements SolrBenchBackend {

  private final Path solrHome;

  private CoreContainer coreContainer;
  private SolrClient client;

  public EmbeddedSolrBackend(Path solrHome) {
    this.solrHome = solrHome;
  }

  @Override
  public void start(int nodeCount) throws Exception {
    log("starting embedded Solr at: " + solrHome.toAbsolutePath());

    if (!Files.exists(solrHome)) {
      Files.createDirectories(solrHome);
      Files.writeString(
          solrHome.resolve("solr.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<solr/>\n");
      Files.createDirectories(solrHome.resolve("configsets"));
    }

    coreContainer = new CoreContainer(solrHome, new Properties());
    coreContainer.load();

    log("done starting embedded Solr");
  }

  @Override
  public SolrClient getClient(String collection) {
    // never cache null (admin request)
    if (collection == null) {
      return new EmbeddedSolrServer(coreContainer, null);
    }
    if (client == null) {
      client = new EmbeddedSolrServer(coreContainer, collection);
    }
    return client;
  }

  @Override
  public void registerConfigset(Path configDir) throws Exception {
    String configSetName = configDir.getFileName().toString();
    Path targetConfDir = solrHome.resolve("configsets").resolve(configSetName).resolve("conf");
    if (Files.exists(targetConfDir)) {
      return;
    }
    Path sourceConfDir = configDir.resolve("conf");
    Files.createDirectories(targetConfDir);
    try (var stream = Files.walk(sourceConfDir)) {
      stream.forEach(
          source -> {
            try {
              Path target = targetConfDir.resolve(sourceConfDir.relativize(source));
              if (Files.isDirectory(source)) {
                Files.createDirectories(target);
              } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
              }
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }
  }

  @Override
  public boolean createCollection(
      String name, String configName, int shards, int replicas, Map<String, String> properties)
      throws Exception {
    if (coreContainer.getCoreDescriptor(name) != null) {
      return false;
    }
    Map<String, String> params = new HashMap<>();
    params.put("configSet", configName);
    params.putAll(properties);
    coreContainer.create(name, params);
    return true;
  }

  @Override
  public void reloadCollection(String name) throws Exception {
    coreContainer.reload(name);
  }

  @Override
  public void close() throws Exception {
    IOUtils.closeQuietly(client);
    if (coreContainer != null) {
      coreContainer.shutdown();
    }
  }
}
