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

import java.nio.file.Path;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;

/** Strategy interface for Solr benchmark infrastructure backends. */
public interface SolrBenchBackend extends AutoCloseable {

  /** Start infrastructure. nodeCount is a hint; remote backends may ignore it. */
  void start(int nodeCount) throws Exception;

  /**
   * Returns a {@link SolrClient} defaulted to the given collection. Implementations may create and
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
  void reloadCollection(String name) throws Exception;

  /** Force merge to target segment count. */
  void forceMerge(String name, int maxSegments) throws Exception;

  /** Wait for background merges. */
  void waitForMerges(String name) throws Exception;

  /** Optional diagnostics; no-op is acceptable. */
  void dumpMetrics(Path file) throws Exception;

  /** Optional diagnostics; no-op is acceptable. */
  void dumpCoreInfo() throws Exception;
}
