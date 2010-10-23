/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.integration.gateway.local;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.gateway.Gateway;
import org.elasticsearch.node.internal.InternalNode;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static org.elasticsearch.common.settings.ImmutableSettings.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
public class LocalGatewayIndexStateTests extends AbstractNodesTests {

    private final ESLogger logger = Loggers.getLogger(LocalGatewayIndexStateTests.class);

    @AfterMethod public void cleanAndCloseNodes() throws Exception {
        for (int i = 0; i < 10; i++) {
            if (node("node" + i) != null) {
                node("node" + i).stop();
                // since we store (by default) the index snapshot under the gateway, resetting it will reset the index data as well
                ((InternalNode) node("node" + i)).injector().getInstance(Gateway.class).reset();
            }
        }
        closeAllNodes();
    }

    @Test public void testSimpleOpenClose() throws Exception {
        logger.info("--> cleaning nodes");
        buildNode("node1", settingsBuilder().put("gateway.type", "local").build());
        buildNode("node2", settingsBuilder().put("gateway.type", "local").build());
        cleanAndCloseNodes();

        logger.info("--> starting 2 nodes");
        startNode("node1", settingsBuilder().put("gateway.type", "local").put("index.number_of_shards", 2).put("index.number_of_replicas", 1).build());
        startNode("node2", settingsBuilder().put("gateway.type", "local").put("index.number_of_shards", 2).put("index.number_of_replicas", 1).build());

        logger.info("--> creating test index");
        client("node1").admin().indices().prepareCreate("test").execute().actionGet();

        logger.info("--> waiting for green status");
        ClusterHealthResponse health = client("node1").admin().cluster().prepareHealth().setWaitForGreenStatus().setWaitForNodes("2").execute().actionGet();
        assertThat(health.timedOut(), equalTo(false));

        ClusterStateResponse stateResponse = client("node1").admin().cluster().prepareState().execute().actionGet();
        assertThat(stateResponse.state().metaData().index("test").state(), equalTo(IndexMetaData.State.OPEN));
        assertThat(stateResponse.state().routingTable().index("test").shards().size(), equalTo(2));
        assertThat(stateResponse.state().routingTable().index("test").shardsWithState(ShardRoutingState.STARTED).size(), equalTo(4));

        logger.info("--> indexing a simple document");
        client("node1").prepareIndex("test", "type1", "1").setSource("field1", "value1").execute().actionGet();

        logger.info("--> closing test index...");
        client("node1").admin().indices().prepareClose("test").execute().actionGet();

        stateResponse = client("node1").admin().cluster().prepareState().execute().actionGet();
        assertThat(stateResponse.state().metaData().index("test").state(), equalTo(IndexMetaData.State.CLOSE));
        assertThat(stateResponse.state().routingTable().index("test"), nullValue());

        logger.info("--> trying to index into a closed index ...");
        try {
            client("node1").prepareIndex("test", "type1", "1").setSource("field1", "value1").execute().actionGet();
            assert false;
        } catch (ClusterBlockException e) {
            // all is well
        }

        logger.info("--> closing nodes...");
        closeNode("node2");
        closeNode("node1");

        logger.info("--> starting nodes again...");
        startNode("node1", settingsBuilder().put("gateway.type", "local").build());
        startNode("node2", settingsBuilder().put("gateway.type", "local").build());

        logger.info("--> waiting for green status");
        health = client("node1").admin().cluster().prepareHealth().setWaitForGreenStatus().setWaitForNodes("2").execute().actionGet();
        assertThat(health.timedOut(), equalTo(false));

        stateResponse = client("node1").admin().cluster().prepareState().execute().actionGet();
        assertThat(stateResponse.state().metaData().index("test").state(), equalTo(IndexMetaData.State.CLOSE));
        assertThat(stateResponse.state().routingTable().index("test"), nullValue());

        logger.info("--> trying to index into a closed index ...");
        try {
            client("node1").prepareIndex("test", "type1", "1").setSource("field1", "value1").execute().actionGet();
            assert false;
        } catch (ClusterBlockException e) {
            // all is well
        }

        logger.info("--> opening index...");
        client("node1").admin().indices().prepareOpen("test").execute().actionGet();

        logger.info("--> waiting for green status");
        health = client("node1").admin().cluster().prepareHealth().setWaitForGreenStatus().setWaitForNodes("2").execute().actionGet();
        assertThat(health.timedOut(), equalTo(false));

        stateResponse = client("node1").admin().cluster().prepareState().execute().actionGet();
        assertThat(stateResponse.state().metaData().index("test").state(), equalTo(IndexMetaData.State.OPEN));
        assertThat(stateResponse.state().routingTable().index("test").shards().size(), equalTo(2));
        assertThat(stateResponse.state().routingTable().index("test").shardsWithState(ShardRoutingState.STARTED).size(), equalTo(4));

        logger.info("--> trying to get the indexed document on the first round (before close and shutdown)");
        GetResponse getResponse = client("node1").prepareGet("test", "type1", "1").execute().actionGet();
        assertThat(getResponse.exists(), equalTo(true));

        logger.info("--> indexing a simple document");
        client("node1").prepareIndex("test", "type1", "2").setSource("field1", "value1").execute().actionGet();
    }
}
