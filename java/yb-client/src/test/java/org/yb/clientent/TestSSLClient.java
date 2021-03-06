// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb.clientent;

import org.yb.client.*;

import java.util.*;

import com.google.protobuf.ByteString;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;

import org.yb.Schema;
import org.yb.ColumnSchema;
import org.yb.YBTestRunner;
import org.yb.master.Master;
import org.yb.minicluster.MiniYBCluster;

import org.yb.util.Timeouts;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;

import static org.yb.AssertionWrappers.assertTrue;
import static org.yb.AssertionWrappers.assertFalse;

@RunWith(value=YBTestRunner.class)
public class TestSSLClient extends TestYBClient {
  private static final String PLACEMENT_CLOUD = "testCloud";
  private static final String PLACEMENT_REGION = "testRegion";
  private static final String PLACEMENT_ZONE = "testZone";
  private static final String LIVE_TS = "live";
  private static final String READ_ONLY_TS = "readOnly";
  private static final String READ_ONLY_NEW_TS = "readOnlyNew";

  public enum TestMode {
    TLS, MUTUAL_TLS_NO_CLIENT_VERIFY, MUTUAL_TLS_CLIENT_VERIFY
  }

  private void setup(TestMode mode) throws Exception {
    destroyMiniCluster();

    certFile = String.format("%s/%s", certsDir(), "ca.crt");
    useIpWithCertificate = true;

    String certDirs = String.format("--certs_dir=%s", certsDir());

    List<String> flagsToAdd = new LinkedList<String>(Arrays.asList(
        "--use_node_to_node_encryption=true", "--use_client_to_server_encryption=true",
        "--allow_insecure_connections=false", certDirs));

    // If not basic TLS, set-up the client certs.
    if (mode != TestMode.TLS) {
      clientCertFile = String.format("%s/%s", certsDir(), "node.127.0.0.100.crt");
      clientKeyFile = String.format("%s/%s", certsDir(), "node.127.0.0.100.key");
      flagsToAdd.add("--node_to_node_encryption_use_client_certificates=true");
    }
    // If verify client hostname mode, set the correct host for the client.
    if (mode == TestMode.MUTUAL_TLS_CLIENT_VERIFY) {
      clientHost = "127.0.0.100";
      flagsToAdd.add("--verify_client_endpoint=true");
    }

    List<List<String>> tserverArgs = new ArrayList<List<String>>();
    tserverArgs.add(flagsToAdd);
    tserverArgs.add(flagsToAdd);
    tserverArgs.add(flagsToAdd);

    createMiniCluster(3, flagsToAdd, tserverArgs);
  }

  /**
   * Test to check that client connection succeeds when built with the correct cert.
   * @throws Exception
   */
  @Test(timeout = 100000)
  public void testClientCorrectCertificate() throws Exception {
    LOG.info("Starting testClientCorrectCertificate");

    setup(TestMode.TLS);

    YBClient myClient = null;

    AsyncYBClient aClient = new AsyncYBClient.AsyncYBClientBuilder(masterAddresses)
                            .sslCertFile(certFile)
                            .build();
    myClient = new YBClient(aClient);
    myClient.waitForMasterLeader(Timeouts.adjustTimeoutSecForBuildType(10000));
    myClient.close();
    myClient = null;
  }

  /**
   * Test to check that client connection fails when SSL is enabled but yb-client has SSL disabled.
   * @throws Exception
   */
  @Test(timeout = 100000)
  public void testClientNoCertificate() throws Exception {
    LOG.info("Starting testClientNoCertificate");

    boolean connectSuccess = true;
    setup(TestMode.TLS);

    YBClient myClient = null;
    AsyncYBClient aClient = new AsyncYBClient.AsyncYBClientBuilder(masterAddresses)
                            .build();
    myClient = new YBClient(aClient);
    try {
      LOG.info("Trying to send an RPC...");
      myClient.waitForMasterLeader(Timeouts.adjustTimeoutSecForBuildType(10000));
    } catch (Exception e) {
      connectSuccess = false;
    }
    myClient.close();
    myClient = null;
    assertFalse(connectSuccess);
  }

  /**
   * Test to check that client connection fails when built with an incorrect cert.
   * @throws Exception
   */
  @Test(timeout = 100000)
  public void testClientIncorrectCertificate() throws Exception {
    LOG.info("Starting testClientIncorrectCertificate");

    boolean connectSuccess = true;
    setup(TestMode.TLS);
    String incorrectCert = String.format("%s/%s", certsDir(), "pseudo.crt");

    YBClient myClient = null;
    AsyncYBClient aClient = new AsyncYBClient.AsyncYBClientBuilder(masterAddresses)
                            .sslCertFile(incorrectCert)
                            .build();
    myClient = new YBClient(aClient);
    try {
      LOG.info("Trying to send an RPC...");
      myClient.waitForMasterLeader(Timeouts.adjustTimeoutSecForBuildType(10000));
    } catch (Exception e) {
      connectSuccess = false;
    }
    myClient.close();
    myClient = null;
    assertFalse(connectSuccess);
  }

  /**
   * Test to check that client connection succeeds with mTLS enabled.
   * @throws Exception
   */
  @Test(timeout = 100000)
  public void testMutualTLS() throws Exception {
    LOG.info("Starting testMutualTLS");

    setup(TestMode.MUTUAL_TLS_NO_CLIENT_VERIFY);

    YBClient myClient = null;

    AsyncYBClient aClient = new AsyncYBClient.AsyncYBClientBuilder(masterAddresses)
                            .sslCertFile(certFile)
                            .sslClientCertFiles(clientCertFile, clientKeyFile)
                            .build();
    myClient = new YBClient(aClient);
    myClient.waitForMasterLeader(Timeouts.adjustTimeoutSecForBuildType(10000));
    myClient.close();
    myClient = null;
  }

  /**
   * Test to check that client connection fails with mTLS enabled when
   * no client certs are provided..
   * @throws Exception
   */
  @Test(timeout = 100000)
  public void testMutualTLSNoClientCert() throws Exception {
    LOG.info("Starting testMutualTLSNoClientCert");

    boolean connectSuccess = true;
    setup(TestMode.MUTUAL_TLS_NO_CLIENT_VERIFY);

    YBClient myClient = null;
    AsyncYBClient aClient = new AsyncYBClient.AsyncYBClientBuilder(masterAddresses)
                            .sslCertFile(certFile)
                            .build();
    myClient = new YBClient(aClient);
    try {
      LOG.info("Trying to send an RPC...");
      myClient.waitForMasterLeader(Timeouts.adjustTimeoutSecForBuildType(10000));
    } catch (Exception e) {
      connectSuccess = false;
    }
    myClient.close();
    myClient = null;
    assertFalse(connectSuccess);
  }

  /**
   * Test to check that client connection succeeds with mTLS and client hostname verification.
   * @throws Exception
   */
  @Test(timeout = 100000)
  public void testMutualTLSClientVerify() throws Exception {
    LOG.info("Starting testMutualTLSClientVerify");

    setup(TestMode.MUTUAL_TLS_CLIENT_VERIFY);

    YBClient myClient = null;

    AsyncYBClient aClient = new AsyncYBClient.AsyncYBClientBuilder(masterAddresses)
                            .sslCertFile(certFile)
                            .sslClientCertFiles(clientCertFile, clientKeyFile)
                            .bindHostAddress(clientHost, clientPort)
                            .build();
    myClient = new YBClient(aClient);
    myClient.waitForMasterLeader(Timeouts.adjustTimeoutSecForBuildType(10000));
    myClient.close();
    myClient = null;
  }

  /**
   * Test to check that client connection fails with mTLS and client hostname verification
   * when using different host that the certificate.
   * @throws Exception
   */
  @Test(timeout = 100000)
  public void testMutualTLSClientVerifyWrongHost() throws Exception {
    LOG.info("Starting testMutualTLSClientVerifyWrongHost");

    setup(TestMode.MUTUAL_TLS_CLIENT_VERIFY);

    boolean connectSuccess = true;
    YBClient myClient = null;

    AsyncYBClient aClient = new AsyncYBClient.AsyncYBClientBuilder(masterAddresses)
                            .sslCertFile(certFile)
                            .sslClientCertFiles(clientCertFile, clientKeyFile)
                            // Cert file being used is of host 127.0.0.100
                            .bindHostAddress("127.0.0.1", clientPort)
                            .build();
    myClient = new YBClient(aClient);
    try {
      LOG.info("Trying to send an RPC...");
      myClient.waitForMasterLeader(Timeouts.adjustTimeoutSecForBuildType(10000));
    } catch (Exception e) {
      connectSuccess = false;
    }
    myClient.close();
    myClient = null;
    assertFalse(connectSuccess);
  }

  private static String certsDir() {
    FileSystem fs = FileSystems.getDefault();
    return fs.getPath(TestUtils.getBinDir()).resolve(fs.getPath("../ent/test_certs")).toString();
  }
}
