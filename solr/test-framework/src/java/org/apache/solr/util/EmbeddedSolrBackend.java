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
package org.apache.solr.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.solr.client.api.model.CreateCollectionRequestBody;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.core.CoreContainer;

/**
 * {@link SolrBackend} backed by an in-process {@link CoreContainer}/{@link EmbeddedSolrServer}. No
 * network or ZooKeeper overhead. {@link CreateCollectionRequestBody} is not supported for
 * multi-shard/replica scenarios — single-core creation only.
 *
 * <p>Data is persisted in the given {@code solrHome} directory.
 */
public class EmbeddedSolrBackend implements SolrBackend {

  private final Path solrHome;
  private CoreContainer coreContainer;
  private EmbeddedSolrServer adminClient;

  public EmbeddedSolrBackend(Path solrHome) {
    this.solrHome = solrHome;
  }

  /**
   * Creates solrHome if necessary, writes a minimal solr.xml, loads the CoreContainer, and
   * initializes the admin client. Must be called before any other method.
   */
  public void start() throws Exception {
    if (!Files.exists(solrHome)) {
      Files.createDirectories(solrHome);
      Files.writeString(
          solrHome.resolve("solr.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<solr/>\n");
      Files.createDirectories(solrHome.resolve("configsets"));
    }
    coreContainer = new CoreContainer(solrHome, new Properties());
    coreContainer.load();
    adminClient = new EmbeddedSolrServer(coreContainer, null);
  }

  @Override
  public SolrClient newClient(String collection) {
    return new EmbeddedSolrServer(coreContainer, collection);
  }

  @Override
  public SolrClient getAdminClient() {
    return adminClient;
  }

  @Override
  public void registerConfigset(Path configDir, String name)
      throws SolrException, SolrBackend.AlreadyExistsException {
    try {
      var ccs = coreContainer.getConfigSetService();
      if (ccs.checkConfigExists(name)) {
        throw new SolrBackend.AlreadyExistsException(name);
      }
      ccs.uploadConfig(name, configDir.resolve("conf"));
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public void createCollection(CreateCollectionRequestBody body)
      throws SolrBackend.AlreadyExistsException, SolrException {
    if (coreContainer.getCoreDescriptor(body.name) != null) {
      throw new SolrBackend.AlreadyExistsException(body.name);
    }
    try {
      Map<String, String> coreParams = new HashMap<>();
      if (body.config != null) {
        coreParams.put("configSet", body.config);
      }
      if (body.properties != null) {
        coreParams.putAll(body.properties);
      }
      coreContainer.create(body.name, coreParams);
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public void reloadCollection(String name) throws SolrException {
    try {
      coreContainer.reload(name);
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public void close() {
    IOUtils.closeQuietly(adminClient);
    if (coreContainer != null) {
      coreContainer.shutdown();
    }
  }
}
