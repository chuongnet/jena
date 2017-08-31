/*
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

package org.seaborne.tdb2.sys;

import java.io.IOException ;
import java.nio.file.Path ;
import java.nio.file.Paths ;
import java.util.HashSet ;
import java.util.Map ;
import java.util.Set ;
import java.util.concurrent.ConcurrentHashMap ;

import org.apache.jena.atlas.io.IO ;
import org.apache.jena.atlas.lib.FileOps ;
import org.apache.jena.sparql.core.DatasetGraph ;
import org.seaborne.dboe.base.file.Location ;
import org.seaborne.dboe.base.file.ProcessFileLock ;
import org.seaborne.dboe.sys.Names ;
import org.seaborne.tdb2.TDBException ;
import org.seaborne.tdb2.repack.DatasetGraphSwitchable ;
import org.seaborne.tdb2.setup.StoreParams ;

// StoreConnection, DatabaseConnection < Connection<X> 

public class DatabaseConnection {
    // ConnectionTracker<X> 
    
    private static Map<Location, DatabaseConnection> cache = new ConcurrentHashMap<>() ;
    
    /** Get the {@code DatabaseConnection} to a location, 
     *  creating the storage structures if it does not exist. */ 
    public synchronized static DatabaseConnection connectCreate(Location location) {
        return connectCreate(location, null) ;
    }

    /** Get the {@code DatabaseConnection} to a location, 
     *  creating the storage structures if it does not exist.
     *  Use the provided {@link StoreParams} - any persistent setting 
     *  already at the location take precedence.
     */ 
    public synchronized static DatabaseConnection connectCreate(Location location, StoreParams params) {
        return make(location, params) ;
    }
    
    /**
     * Return a {@code StoreConnection} for a particular location,
     * creating it if it does not exist in storage.
     */
    private synchronized static DatabaseConnection make(Location location, StoreParams params) {
        if ( location.isMemUnique() ) {
            // Uncached, in-memory. 
            DatasetGraph dsg = DatabaseOps.create(location);
            DatabaseConnection dbConn = new DatabaseConnection(dsg, location, null);
            return dbConn;
        }
        // Cached by Location. Named in-memory or on-disk.
        DatabaseConnection dbConn = cache.computeIfAbsent(location, (loc)->buildForCache(loc, params));
        return dbConn ;
    }
    
    private static DatabaseConnection buildForCache(Location location, StoreParams params) {
        if ( location.isMemUnique() ) {
            throw new TDBException("Can't buildForCache a memory-unique location");
        }
        ProcessFileLock lock = null;
        if (SystemTDB.DiskLocationMultiJvmUsagePrevention && ! location.isMem() ) {
            lock = lockForLocation(location);
            // Take the lock.  This is atomic.
            lock.lockEx();
        }
        DatasetGraph dsg = DatabaseOps.create(location);
        return new DatabaseConnection(dsg, location, lock) ;
    }
    
//    private static DatasetGraph buildMem(Location location, StoreParams params) {
//        return StoreConnection.connectCreate(location, params).getDatasetGraph(); 
//    }
//
//    private static DatasetGraph buildDisk(Location location, StoreParams params) {
//        return DatabaseOps.create(location);
//    }

    // DRY
    /** Create or fetch a {@link ProcessFileLock} for a Location */
    public static ProcessFileLock lockForLocation(Location location) {
        FileOps.ensureDir(location.getDirectoryPath());
        String lockFilename = location.getPath(Names.TDB_LOCK_FILE);
        Path path = Paths.get(lockFilename);
        try {
            path.toFile().createNewFile();
        } catch(IOException ex) { IO.exception(ex); return null; }
        return ProcessFileLock.create(lockFilename);
    }
    
    /** Stop managing a location. Use with great care (testing only). */
    public static synchronized void expel(Location location, boolean force) {
        DatabaseConnection dbConn = cache.get(location) ;
        if (dbConn == null) return ;
        cache.remove(location) ;
        dbConn.isValid = false;
        //dbConn.datasetGraph = null;
        //dbConn.datasetGraphSwitchable = null;
        StoreConnection.expel(location, force);
        
        //    if (!force && sConn.transactionManager.activeTransactions()) 
        //        throw new TDBTransactionException("Can't expel: Active transactions for location: " + location) ;

        cache.remove(location) ;
        // Release the lock after the cache is emptied.
        if (SystemTDB.DiskLocationMultiJvmUsagePrevention && ! location.isMem() ) {
            if ( ! dbConn.lock.isLockedHere() )
                SystemTDB.errlog.warn("Location " + location.getDirectoryPath() + " was not locked by this process.");
            dbConn.lock.unlock();
            ProcessFileLock.release(dbConn.lock);
        }
    }
    
    /** 
     * Stop managing all locations. 
     * Use with extreme care.
     * This is intended to support internal testing.
     */
    public static synchronized void reset() {
        // Copy to avoid potential CME.
        Set<Location> x = new HashSet<>(cache.keySet()) ;
        for (Location loc : x)
            expel(loc, true) ;
        cache.clear() ;
        StoreConnection.reset();
    }
    
    
    // One of the other.
    private final DatasetGraphSwitchable   datasetGraphSwitchable;
    private final DatasetGraph             datasetGraph;
    // This is the location of the TDB2 container directory. 
    private final Location          location ;
    private final ProcessFileLock   lock ;
    private boolean                 isValid = true ;
    
    private DatabaseConnection(DatasetGraph dsg, Location location, ProcessFileLock fileLock)
    {
        this.datasetGraph = dsg;
        this.datasetGraphSwitchable =  ( dsg instanceof DatasetGraphSwitchable ) ? (DatasetGraphSwitchable )dsg : null;
        this.location = location ;
        this.lock = fileLock;
    }

    public DatasetGraph getDatasetGraph() {
        return datasetGraph;
    }

    public DatasetGraphSwitchable getDatasetGraphSwitchable() {
        return datasetGraphSwitchable;
    }
    
    public Location getLocation() {
        return location ;
    }
    
    public ProcessFileLock getLock() {
        return lock ;
    }
}
