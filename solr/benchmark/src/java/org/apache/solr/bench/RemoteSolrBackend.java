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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.common.util.IOUtils;

/**
 * {@link SolrBenchBackend} implementation that connects to a pre-existing remote Solr instance.
 * Configure the URL via the {@code solr.bench.url} system property (e.g. {@code
 * http://localhost:8983/solr}).
 */
public class RemoteSolrBackend implements SolrBenchBackend {

  private HttpJettySolrClient client;
  protected String baseUrl;

  @Override
  public void start(int nodeCount) throws Exception {
    baseUrl = System.getProperty("solr.bench.url");
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalStateException(
          "solr.bench.backend=remote requires -Dsolr.bench.url=http://host:port/solr");
    }
  }

  @Override
  public SolrClient getClient(String collection) {
    if (client == null) {
      client = createClient(collection);
    }
    return client;
  }

  @Override
  public void registerConfigset(Path configDir) throws Exception {
    String configSetName = configDir.getFileName().toString();
    try (HttpJettySolrClient adminClient = new HttpJettySolrClient.Builder(baseUrl).build()) {
      List<String> existing = new ConfigSetAdminRequest.List().process(adminClient).getConfigSets();
      if (existing == null || !existing.contains(configSetName)) {
        Path tempZip = Files.createTempFile("configset-", ".zip");
        try {
          zipDirectory(configDir.resolve("conf"), tempZip);
          new ConfigSetAdminRequest.Upload()
              .setConfigSetName(configSetName)
              .setUploadFile(tempZip, "application/zip")
              .process(adminClient);
        } finally {
          Files.deleteIfExists(tempZip);
        }
      }
    }
  }

  @Override
  public boolean createCollection(
      String name, String configName, int shards, int replicas, Map<String, String> properties)
      throws Exception {
    SolrClient c = getClient(name);
    CollectionAdminResponse listResponse = new CollectionAdminRequest.List().process(c);
    @SuppressWarnings("unchecked")
    List<String> collections = (List<String>) listResponse.getResponse().get("collections");
    if (collections != null && collections.contains(name)) {
      return false;
    }
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(name, configName, shards, replicas);
    if (!properties.isEmpty()) {
      create.setProperties(properties);
    }
    create.process(c);
    return true;
  }

  private static void zipDirectory(Path sourceDir, Path targetZip) throws IOException {
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
      Files.walk(sourceDir)
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                try {
                  zos.putNextEntry(new ZipEntry(sourceDir.relativize(file).toString()));
                  Files.copy(file, zos);
                  zos.closeEntry();
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }

  protected HttpJettySolrClient createClient(String collection) {
    return new HttpJettySolrClient.Builder(baseUrl).withDefaultCollection(collection).build();
  }

  @Override
  public void reloadCollection(String name) throws Exception {
    client.request(CollectionAdminRequest.reloadCollection(name), null);
  }

  @Override
  public void forceMerge(String name, int maxSegments) throws Exception {
    UpdateRequest optimizeRequest = new UpdateRequest();
    optimizeRequest.setAction(UpdateRequest.ACTION.OPTIMIZE, false, true, maxSegments);
    client.request(optimizeRequest, name);
  }

  @Override
  public void waitForMerges(String name) throws Exception {
    forceMerge(name, Integer.MAX_VALUE);
  }

  @Override
  public void dumpMetrics(Path file) throws Exception {
    // TODO: fetch from /admin/metrics API
  }

  @Override
  public void dumpCoreInfo() throws Exception {
    // no-op for remote backend
  }

  @Override
  public void close() throws Exception {
    IOUtils.closeQuietly(client);
  }
}
