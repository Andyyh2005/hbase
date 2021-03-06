/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client.replication;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.replication.ReplicationException;
import org.apache.hadoop.hbase.replication.ReplicationFactory;
import org.apache.hadoop.hbase.replication.ReplicationPeer;
import org.apache.hadoop.hbase.replication.ReplicationPeerConfig;
import org.apache.hadoop.hbase.replication.ReplicationPeerZKImpl;
import org.apache.hadoop.hbase.replication.ReplicationPeers;
import org.apache.hadoop.hbase.replication.ReplicationQueuesClient;
import org.apache.hadoop.hbase.replication.ReplicationQueuesClientArguments;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;

/**
 * <p>
 * This class provides the administrative interface to HBase cluster
 * replication.
 * </p>
 * <p>
 * Adding a new peer results in creating new outbound connections from every
 * region server to a subset of region servers on the slave cluster. Each
 * new stream of replication will start replicating from the beginning of the
 * current WAL, meaning that edits from that past will be replicated.
 * </p>
 * <p>
 * Removing a peer is a destructive and irreversible operation that stops
 * all the replication streams for the given cluster and deletes the metadata
 * used to keep track of the replication state.
 * </p>
 * <p>
 * To see which commands are available in the shell, type
 * <code>replication</code>.
 * </p>
 *
 * @deprecated use {@link org.apache.hadoop.hbase.client.Admin} instead.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
@Deprecated
public class ReplicationAdmin implements Closeable {
  private static final Log LOG = LogFactory.getLog(ReplicationAdmin.class);

  public static final String TNAME = "tableName";
  public static final String CFNAME = "columnFamilyName";

  // only Global for now, can add other type
  // such as, 1) no global replication, or 2) the table is replicated to this cluster, etc.
  public static final String REPLICATIONTYPE = "replicationType";
  public static final String REPLICATIONGLOBAL =
      Integer.toString(HConstants.REPLICATION_SCOPE_GLOBAL);
  public static final String REPLICATIONSERIAL =
      Integer.toString(HConstants.REPLICATION_SCOPE_SERIAL);

  private final Connection connection;
  // TODO: replication should be managed by master. All the classes except ReplicationAdmin should
  // be moved to hbase-server. Resolve it in HBASE-11392.
  private final ReplicationQueuesClient replicationQueuesClient;
  private final ReplicationPeers replicationPeers;
  /**
   * A watcher used by replicationPeers and replicationQueuesClient. Keep reference so can dispose
   * on {@link #close()}.
   */
  private final ZooKeeperWatcher zkw;

  private Admin admin;

  /**
   * Constructor that creates a connection to the local ZooKeeper ensemble.
   * @param conf Configuration to use
   * @throws IOException if an internal replication error occurs
   * @throws RuntimeException if replication isn't enabled.
   */
  public ReplicationAdmin(Configuration conf) throws IOException {
    this.connection = ConnectionFactory.createConnection(conf);
    admin = connection.getAdmin();
    try {
      zkw = createZooKeeperWatcher();
      try {
        this.replicationQueuesClient =
            ReplicationFactory.getReplicationQueuesClient(new ReplicationQueuesClientArguments(conf,
            this.connection, zkw));
        this.replicationQueuesClient.init();
        this.replicationPeers = ReplicationFactory.getReplicationPeers(zkw, conf,
          this.replicationQueuesClient, this.connection);
        this.replicationPeers.init();
      } catch (Exception exception) {
        if (zkw != null) {
          zkw.close();
        }
        throw exception;
      }
    } catch (Exception exception) {
      connection.close();
      if (exception instanceof IOException) {
        throw (IOException) exception;
      } else if (exception instanceof RuntimeException) {
        throw (RuntimeException) exception;
      } else {
        throw new IOException("Error initializing the replication admin client.", exception);
      }
    }
  }

  private ZooKeeperWatcher createZooKeeperWatcher() throws IOException {
    // This Abortable doesn't 'abort'... it just logs.
    return new ZooKeeperWatcher(connection.getConfiguration(), "ReplicationAdmin", new Abortable() {
      @Override
      public void abort(String why, Throwable e) {
        LOG.error(why, e);
        // We used to call system.exit here but this script can be embedded by other programs that
        // want to do replication stuff... so inappropriate calling System.exit. Just log for now.
      }

      @Override
      public boolean isAborted() {
        return false;
      }
    });
  }

  /**
   * Add a new remote slave cluster for replication.
   * @param id a short name that identifies the cluster
   * @param peerConfig configuration for the replication slave cluster
   * @param tableCfs the table and column-family list which will be replicated for this peer.
   * A map from tableName to column family names. An empty collection can be passed
   * to indicate replicating all column families. Pass null for replicating all table and column
   * families
   * @deprecated as release of 2.0.0, and it will be removed in 3.0.0,
   * use {@link #addPeer(String, ReplicationPeerConfig)} instead.
   */
  @Deprecated
  public void addPeer(String id, ReplicationPeerConfig peerConfig,
      Map<TableName, ? extends Collection<String>> tableCfs) throws ReplicationException,
      IOException {
    if (tableCfs != null) {
      peerConfig.setTableCFsMap(tableCfs);
    }
    this.admin.addReplicationPeer(id, peerConfig);
  }

  /**
   * Add a new remote slave cluster for replication.
   * @param id a short name that identifies the cluster
   * @param peerConfig configuration for the replication slave cluster
   */
  public void addPeer(String id, ReplicationPeerConfig peerConfig) throws ReplicationException,
      IOException {
    checkNamespacesAndTableCfsConfigConflict(peerConfig.getNamespaces(),
      peerConfig.getTableCFsMap());
    this.admin.addReplicationPeer(id, peerConfig);
  }

  /**
   *  @deprecated as release of 2.0.0, and it will be removed in 3.0.0
   * */
  @Deprecated
  public static Map<TableName, List<String>> parseTableCFsFromConfig(String tableCFsConfig) {
    return ReplicationSerDeHelper.parseTableCFsFromConfig(tableCFsConfig);
  }

  public void updatePeerConfig(String id, ReplicationPeerConfig peerConfig) throws IOException {
    this.admin.updateReplicationPeerConfig(id, peerConfig);
  }

  /**
   * Removes a peer cluster and stops the replication to it.
   * @param id a short name that identifies the cluster
   */
  public void removePeer(String id) throws IOException {
    this.admin.removeReplicationPeer(id);
  }

  /**
   * Restart the replication stream to the specified peer.
   * @param id a short name that identifies the cluster
   */
  public void enablePeer(String id) throws IOException {
    this.admin.enableReplicationPeer(id);
  }

  /**
   * Stop the replication stream to the specified peer.
   * @param id a short name that identifies the cluster
   */
  public void disablePeer(String id) throws IOException {
    this.admin.disableReplicationPeer(id);
  }

  /**
   * Get the number of slave clusters the local cluster has.
   * @return number of slave clusters
   */
  public int getPeersCount() {
    return this.replicationPeers.getAllPeerIds().size();
  }

  public Map<String, ReplicationPeerConfig> listPeerConfigs() {
    return this.replicationPeers.getAllPeerConfigs();
  }

  public ReplicationPeerConfig getPeerConfig(String id) throws IOException {
    return admin.getReplicationPeerConfig(id);
  }

  /**
   * Get the replicable table-cf config of the specified peer.
   * @param id a short name that identifies the cluster
   * @deprecated as release of 2.0.0, and it will be removed in 3.0.0,
   * use {@link #getPeerConfig(String)} instead.
   * */
  @Deprecated
  public String getPeerTableCFs(String id) throws IOException {
    ReplicationPeerConfig peerConfig = admin.getReplicationPeerConfig(id);
    return ReplicationSerDeHelper.convertToString(peerConfig.getTableCFsMap());
  }

  /**
   * Append the replicable table-cf config of the specified peer
   * @param id a short that identifies the cluster
   * @param tableCfs table-cfs config str
   * @throws ReplicationException
   * @throws IOException
   * @deprecated as release of 2.0.0, and it will be removed in 3.0.0,
   * use {@link #appendPeerTableCFs(String, Map)} instead.
   */
  @Deprecated
  public void appendPeerTableCFs(String id, String tableCfs) throws ReplicationException,
      IOException {
    appendPeerTableCFs(id, ReplicationSerDeHelper.parseTableCFsFromConfig(tableCfs));
  }

  /**
   * Append the replicable table-cf config of the specified peer
   * @param id a short that identifies the cluster
   * @param tableCfs A map from tableName to column family names
   * @throws ReplicationException
   * @throws IOException
   */
  public void appendPeerTableCFs(String id, Map<TableName, ? extends Collection<String>> tableCfs)
      throws ReplicationException, IOException {
    if (tableCfs == null) {
      throw new ReplicationException("tableCfs is null");
    }
    ReplicationPeerConfig peerConfig = admin.getReplicationPeerConfig(id);
    Map<TableName, List<String>> preTableCfs = peerConfig.getTableCFsMap();
    if (preTableCfs == null) {
      setPeerTableCFs(id, tableCfs);
      return;
    }
    for (Map.Entry<TableName, ? extends Collection<String>> entry : tableCfs.entrySet()) {
      TableName table = entry.getKey();
      Collection<String> appendCfs = entry.getValue();
      if (preTableCfs.containsKey(table)) {
        List<String> cfs = preTableCfs.get(table);
        if (cfs == null || appendCfs == null || appendCfs.isEmpty()) {
          preTableCfs.put(table, null);
        } else {
          Set<String> cfSet = new HashSet<String>(cfs);
          cfSet.addAll(appendCfs);
          preTableCfs.put(table, Lists.newArrayList(cfSet));
        }
      } else {
        if (appendCfs == null || appendCfs.isEmpty()) {
          preTableCfs.put(table, null);
        } else {
          preTableCfs.put(table, Lists.newArrayList(appendCfs));
        }
      }
    }
    updatePeerConfig(id, peerConfig);
  }

  /**
   * Remove some table-cfs from table-cfs config of the specified peer
   * @param id a short name that identifies the cluster
   * @param tableCf table-cfs config str
   * @throws ReplicationException
   * @throws IOException
   * @deprecated as release of 2.0.0, and it will be removed in 3.0.0,
   * use {@link #removePeerTableCFs(String, Map)} instead.
   */
  @Deprecated
  public void removePeerTableCFs(String id, String tableCf) throws ReplicationException,
      IOException {
    removePeerTableCFs(id, ReplicationSerDeHelper.parseTableCFsFromConfig(tableCf));
  }

  /**
   * Remove some table-cfs from config of the specified peer
   * @param id a short name that identifies the cluster
   * @param tableCfs A map from tableName to column family names
   * @throws ReplicationException
   * @throws IOException
   */
  public void removePeerTableCFs(String id, Map<TableName, ? extends Collection<String>> tableCfs)
      throws ReplicationException, IOException {
    if (tableCfs == null) {
      throw new ReplicationException("tableCfs is null");
    }
    ReplicationPeerConfig peerConfig = admin.getReplicationPeerConfig(id);
    Map<TableName, List<String>> preTableCfs = peerConfig.getTableCFsMap();
    if (preTableCfs == null) {
      throw new ReplicationException("Table-Cfs for peer" + id + " is null");
    }
    for (Map.Entry<TableName, ? extends Collection<String>> entry: tableCfs.entrySet()) {

      TableName table = entry.getKey();
      Collection<String> removeCfs = entry.getValue();
      if (preTableCfs.containsKey(table)) {
        List<String> cfs = preTableCfs.get(table);
        if (cfs == null && (removeCfs == null || removeCfs.isEmpty())) {
          preTableCfs.remove(table);
        } else if (cfs != null && (removeCfs != null && !removeCfs.isEmpty())) {
          Set<String> cfSet = new HashSet<String>(cfs);
          cfSet.removeAll(removeCfs);
          if (cfSet.isEmpty()) {
            preTableCfs.remove(table);
          } else {
            preTableCfs.put(table, Lists.newArrayList(cfSet));
          }
        } else if (cfs == null && (removeCfs != null && !removeCfs.isEmpty())) {
          throw new ReplicationException("Cannot remove cf of table: " + table
              + " which doesn't specify cfs from table-cfs config in peer: " + id);
        } else if (cfs != null && (removeCfs == null || removeCfs.isEmpty())) {
          throw new ReplicationException("Cannot remove table: " + table
              + " which has specified cfs from table-cfs config in peer: " + id);
        }
      } else {
        throw new ReplicationException("No table: " + table + " in table-cfs config of peer: " + id);
      }
    }
    updatePeerConfig(id, peerConfig);
  }

  /**
   * Set the replicable table-cf config of the specified peer
   * @param id a short name that identifies the cluster
   * @param tableCfs the table and column-family list which will be replicated for this peer.
   * A map from tableName to column family names. An empty collection can be passed
   * to indicate replicating all column families. Pass null for replicating all table and column
   * families
   */
  public void setPeerTableCFs(String id, Map<TableName, ? extends Collection<String>> tableCfs)
      throws IOException {
    ReplicationPeerConfig peerConfig = getPeerConfig(id);
    peerConfig.setTableCFsMap(tableCfs);
    updatePeerConfig(id, peerConfig);
  }

  /**
   * Get the state of the specified peer cluster
   * @param id String format of the Short name that identifies the peer,
   * an IllegalArgumentException is thrown if it doesn't exist
   * @return true if replication is enabled to that peer, false if it isn't
   */
  public boolean getPeerState(String id) throws ReplicationException {
    return this.replicationPeers.getStatusOfPeerFromBackingStore(id);
  }

  @Override
  public void close() throws IOException {
    if (this.zkw != null) {
      this.zkw.close();
    }
    if (this.connection != null) {
      this.connection.close();
    }
    admin.close();
  }


  /**
   * Find all column families that are replicated from this cluster
   * @return the full list of the replicated column families of this cluster as:
   *        tableName, family name, replicationType
   *
   * Currently replicationType is Global. In the future, more replication
   * types may be extended here. For example
   *  1) the replication may only apply to selected peers instead of all peers
   *  2) the replicationType may indicate the host Cluster servers as Slave
   *     for the table:columnFam.
   */
  public List<HashMap<String, String>> listReplicated() throws IOException {
    List<HashMap<String, String>> replicationColFams = new ArrayList<HashMap<String, String>>();

    Admin admin = connection.getAdmin();
    HTableDescriptor[] tables;
    try {
      tables = admin.listTables();
    } finally {
      if (admin!= null) admin.close();
    }

    for (HTableDescriptor table : tables) {
      HColumnDescriptor[] columns = table.getColumnFamilies();
      String tableName = table.getNameAsString();
      for (HColumnDescriptor column : columns) {
        if (column.getScope() != HConstants.REPLICATION_SCOPE_LOCAL) {
          // At this moment, the columfam is replicated to all peers
          HashMap<String, String> replicationEntry = new HashMap<String, String>();
          replicationEntry.put(TNAME, tableName);
          replicationEntry.put(CFNAME, column.getNameAsString());
          replicationEntry.put(REPLICATIONTYPE,
              column.getScope() == HConstants.REPLICATION_SCOPE_GLOBAL ?
                  REPLICATIONGLOBAL :
                  REPLICATIONSERIAL);
          replicationColFams.add(replicationEntry);
        }
      }
    }

    return replicationColFams;
  }

  /**
   * Enable a table's replication switch.
   * @param tableName name of the table
   * @throws IOException if a remote or network exception occurs
   */
  public void enableTableRep(final TableName tableName) throws IOException {
    if (tableName == null) {
      throw new IllegalArgumentException("Table name cannot be null");
    }
    try (Admin admin = this.connection.getAdmin()) {
      if (!admin.tableExists(tableName)) {
        throw new TableNotFoundException("Table '" + tableName.getNameAsString()
            + "' does not exists.");
      }
    }
    byte[][] splits = getTableSplitRowKeys(tableName);
    checkAndSyncTableDescToPeers(tableName, splits);
    setTableRep(tableName, true);
  }

  /**
   * Disable a table's replication switch.
   * @param tableName name of the table
   * @throws IOException if a remote or network exception occurs
   */
  public void disableTableRep(final TableName tableName) throws IOException {
    if (tableName == null) {
      throw new IllegalArgumentException("Table name is null");
    }
    try (Admin admin = this.connection.getAdmin()) {
      if (!admin.tableExists(tableName)) {
        throw new TableNotFoundException("Table '" + tableName.getNamespaceAsString()
            + "' does not exists.");
      }
    }
    setTableRep(tableName, false);
  }

  /**
   * Get the split row keys of table
   * @param tableName table name
   * @return array of split row keys
   * @throws IOException
   */
  private byte[][] getTableSplitRowKeys(TableName tableName) throws IOException {
    try (RegionLocator locator = connection.getRegionLocator(tableName);) {
      byte[][] startKeys = locator.getStartKeys();
      if (startKeys.length == 1) {
        return null;
      }
      byte[][] splits = new byte[startKeys.length - 1][];
      for (int i = 1; i < startKeys.length; i++) {
        splits[i - 1] = startKeys[i];
      }
      return splits;
    }
  }

  /**
   * Connect to peer and check the table descriptor on peer:
   * <ol>
   * <li>Create the same table on peer when not exist.</li>
   * <li>Throw exception if the table exists on peer cluster but descriptors are not same.</li>
   * </ol>
   * @param tableName name of the table to sync to the peer
   * @param splits table split keys
   * @throws IOException
   */
  private void checkAndSyncTableDescToPeers(final TableName tableName, final byte[][] splits)
      throws IOException {
    List<ReplicationPeer> repPeers = listReplicationPeers();
    if (repPeers == null || repPeers.size() <= 0) {
      throw new IllegalArgumentException("Found no peer cluster for replication.");
    }

    final TableName onlyTableNameQualifier = TableName.valueOf(tableName.getQualifierAsString());

    for (ReplicationPeer repPeer : repPeers) {
      Map<TableName, List<String>> tableCFMap = repPeer.getTableCFs();
      // TODO Currently peer TableCFs will not include namespace so we need to check only for table
      // name without namespace in it. Need to correct this logic once we fix HBASE-11386.
      if (tableCFMap != null && !tableCFMap.containsKey(onlyTableNameQualifier)) {
        continue;
      }

      Configuration peerConf = repPeer.getConfiguration();
      HTableDescriptor htd = null;
      try (Connection conn = ConnectionFactory.createConnection(peerConf);
          Admin admin = this.connection.getAdmin();
          Admin repHBaseAdmin = conn.getAdmin()) {
        htd = admin.getTableDescriptor(tableName);
        HTableDescriptor peerHtd = null;
        if (!repHBaseAdmin.tableExists(tableName)) {
          repHBaseAdmin.createTable(htd, splits);
        } else {
          peerHtd = repHBaseAdmin.getTableDescriptor(tableName);
          if (peerHtd == null) {
            throw new IllegalArgumentException("Failed to get table descriptor for table "
                + tableName.getNameAsString() + " from peer cluster " + repPeer.getId());
          } else if (!peerHtd.equals(htd)) {
            throw new IllegalArgumentException("Table " + tableName.getNameAsString()
                + " exists in peer cluster " + repPeer.getId()
                + ", but the table descriptors are not same when compared with source cluster."
                + " Thus can not enable the table's replication switch.");
          }
        }
      }
    }
  }

  @VisibleForTesting
  public void peerAdded(String id) throws ReplicationException {
    this.replicationPeers.peerConnected(id);
  }

  @VisibleForTesting
  List<ReplicationPeer> listReplicationPeers() {
    Map<String, ReplicationPeerConfig> peers = listPeerConfigs();
    if (peers == null || peers.size() <= 0) {
      return null;
    }
    List<ReplicationPeer> listOfPeers = new ArrayList<ReplicationPeer>(peers.size());
    for (Entry<String, ReplicationPeerConfig> peerEntry : peers.entrySet()) {
      String peerId = peerEntry.getKey();
      try {
        Pair<ReplicationPeerConfig, Configuration> pair = this.replicationPeers.getPeerConf(peerId);
        Configuration peerConf = pair.getSecond();
        ReplicationPeer peer = new ReplicationPeerZKImpl(zkw, pair.getSecond(),
          peerId, pair.getFirst(), this.connection);
        listOfPeers.add(peer);
      } catch (ReplicationException e) {
        LOG.warn("Failed to get valid replication peers. "
            + "Error connecting to peer cluster with peerId=" + peerId + ". Error message="
            + e.getMessage());
        LOG.debug("Failure details to get valid replication peers.", e);
        continue;
      }
    }
    return listOfPeers;
  }

  /**
   * Set the table's replication switch if the table's replication switch is already not set.
   * @param tableName name of the table
   * @param isRepEnabled is replication switch enable or disable
   * @throws IOException if a remote or network exception occurs
   */
  private void setTableRep(final TableName tableName, boolean isRepEnabled) throws IOException {
    Admin admin = null;
    try {
      admin = this.connection.getAdmin();
      HTableDescriptor htd = admin.getTableDescriptor(tableName);
      if (isTableRepEnabled(htd) ^ isRepEnabled) {
        for (HColumnDescriptor hcd : htd.getFamilies()) {
          hcd.setScope(isRepEnabled ? HConstants.REPLICATION_SCOPE_GLOBAL
              : HConstants.REPLICATION_SCOPE_LOCAL);
        }
        admin.modifyTable(tableName, htd);
      }
    } finally {
      if (admin != null) {
        try {
          admin.close();
        } catch (IOException e) {
          LOG.warn("Failed to close admin connection.");
          LOG.debug("Details on failure to close admin connection.", e);
        }
      }
    }
  }

  /**
   * @param htd table descriptor details for the table to check
   * @return true if table's replication switch is enabled
   */
  private boolean isTableRepEnabled(HTableDescriptor htd) {
    for (HColumnDescriptor hcd : htd.getFamilies()) {
      if (hcd.getScope() != HConstants.REPLICATION_SCOPE_GLOBAL
          && hcd.getScope() != HConstants.REPLICATION_SCOPE_SERIAL) {
        return false;
      }
    }
    return true;
  }

  /**
   * Set a namespace in the peer config means that all tables in this namespace
   * will be replicated to the peer cluster.
   *
   * 1. If you already have set a namespace in the peer config, then you can't set any table
   *    of this namespace to the peer config.
   * 2. If you already have set a table in the peer config, then you can't set this table's
   *    namespace to the peer config.
   *
   * @param namespaces
   * @param tableCfs
   * @throws ReplicationException
   */
  private void checkNamespacesAndTableCfsConfigConflict(Set<String> namespaces,
      Map<TableName, ? extends Collection<String>> tableCfs) throws ReplicationException {
    if (namespaces == null || namespaces.isEmpty()) {
      return;
    }
    if (tableCfs == null || tableCfs.isEmpty()) {
      return;
    }
    for (Map.Entry<TableName, ? extends Collection<String>> entry : tableCfs.entrySet()) {
      TableName table = entry.getKey();
      if (namespaces.contains(table.getNamespaceAsString())) {
        throw new ReplicationException(
            "Table-cfs config conflict with namespaces config in peer");
      }
    }
  }
}
