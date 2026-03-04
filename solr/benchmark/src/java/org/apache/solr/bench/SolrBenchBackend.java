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

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.MetricsRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.InputStreamResponseParser;
import org.apache.solr.common.params.SolrParams;

/** Strategy interface for Solr benchmark infrastructure backends. */
public interface SolrBenchBackend extends AutoCloseable {
  // TODO should be replaced by SolrClientTestRule

  /** Start infrastructure. nodeCount is a hint; remote backends may ignore it. */
  void start(int nodeCount) throws Exception;

  /**
   * Returns a {@link SolrClient} defaulted to the given collection. Implementations will create and
   * cache the client lazily on first call.
   */
  SolrClient getClient(String collection) throws Exception;

  /**
   * Ensures a configset is registered on the server. If the configset already exists this is a
   * no-op. The configset name is derived as {@code getFileName()}. It must not be "conf".
   *
   * <p>Must be called after {@link #start(int)} and before {@link #createCollection}.
   */
  void registerConfigset(Path configDir) throws Exception;

  /**
   * Create a collection if it does not already exist. properties are set via
   * CollectionAdminRequest.Create.setProperties() for solrconfig.xml substitution.
   *
   * @return {@code true} if the collection was just created and needs data loaded; {@code false} if
   *     it already existed and can be used as-is.
   */
  boolean createCollection(
      String name, String configName, int shards, int replicas, Map<String, String> properties)
      throws Exception;

  /** Reload a collection (typically to clear caches). */
  default void reloadCollection(String name) throws Exception {
    try (var client = getClient(null)) {
      client.request(CollectionAdminRequest.reloadCollection(name));
    }
  }

  /** Force merge (AKA Solr "optimize") to target segment count. */
  default void forceMerge(String name, int maxSegments) throws Exception {
    new UpdateRequest()
        .setAction(UpdateRequest.ACTION.OPTIMIZE, false, true, maxSegments)
        .process(getClient(name));
  }

  /** Wait for background merges. */
  default void waitForMerges(String name) throws Exception {
    forceMerge(name, Integer.MAX_VALUE);
  }

  /** Optional diagnostics; no-op is acceptable. */
  default void dumpMetrics(PrintStream out) throws Exception {
    var request = new MetricsRequest();
    request.setResponseParser(new InputStreamResponseParser("prometheus"));
    try (var client = getClient(null)) {
      var response = request.process(client);
      out.println(InputStreamResponseParser.consumeResponseToString(response.getResponse()));
    }
  }

  /** Optional diagnostics; no-op is acceptable. */
  default void dumpCoreInfo(PrintStream out) throws Exception {
    // note using v1 as v2 is experimental and also not supported by EmbeddedSolrServer
    var request =
        new GenericSolrRequest(
            SolrRequest.METHOD.GET, "/admin/cores", SolrParams.of("indexInfo", "true"));
    request.setResponseParser(new InputStreamResponseParser("json"));
    try (var client = getClient(null)) {
      var response = request.process(client);
      out.println(InputStreamResponseParser.consumeResponseToString(response.getResponse()));
    }
  }
}
