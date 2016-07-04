package org.nectarframework.base.service.datastore;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;

import org.nectarframework.base.exception.ConfigurationException;
import org.nectarframework.base.service.ServiceUnavailableException;
import org.nectarframework.base.service.cache.CacheService;
import org.nectarframework.base.service.log.Log;
import org.nectarframework.base.service.mysql.MysqlPreparedStatement;
import org.nectarframework.base.service.mysql.MysqlService;
import org.nectarframework.base.service.mysql.MysqlTransactionHandle;
import org.nectarframework.base.service.mysql.ResultRow;
import org.nectarframework.base.service.mysql.ResultTable;
import org.nectarframework.base.tools.ByteArray;
import org.nectarframework.base.tools.StringTools;

/**
 * This service allows quick and easy access to common game data objects that
 * are mostly read only and rarely updated.
 * 
 * @author skander
 *
 */
public class MysqlDataStoreService extends DataStoreService {
 
	private MysqlService mysqlService;
	private CacheService cacheService;

	@Override
	public void checkParameters() throws ConfigurationException {

	}

	@Override
	protected boolean _init() {
		return true;
	}

	@Override
	public boolean establishDependancies() throws ServiceUnavailableException {
		mysqlService = (MysqlService) dependancy(MysqlService.class);
		cacheService = (CacheService) dependancy(CacheService.class);
		return true;
	}

	@Override
	protected boolean run() {
		return true;
	}

	@Override
	protected boolean shutdown() {
		return true;
	}

	private Collection<DataStoreObject> loadFromQuery(MysqlPreparedStatement mps, DataStoreObjectDescriptor dsod) throws SQLException {

		LinkedList<DataStoreObject> dsoList = new LinkedList<DataStoreObject>();
		ResultTable rt = mysqlService.select(mps);
		for (ResultRow rr : rt) {
			DataStoreObject newDso = null;
			try {
				newDso = dsod.newDsoInstance();
			} catch (InstantiationException e) {
				Log.fatal(e);
				return null;
			} catch (IllegalAccessException e) {
				Log.fatal(e);
				return null;
			}
			newDso.loadFromResultRow(rr);
			cacheService.add(cacheKey(dsod, newDso.getPrimaryKey()), newDso.toBytes().getBytes());
			dsoList.add(newDso);
		}
		return dsoList;

	}

	@Override
	public Collection<DataStoreObject> loadRange(DataStoreObjectDescriptor dsod, DataStoreKey startKey, DataStoreKey endKey) throws Exception {
		// TODO Auto-generated method stub
		return null;
	}

	public Collection<DataStoreObject> loadAll(DataStoreObjectDescriptor dsod) throws Exception {
		String sqlQuery = "SELECT " + StringTools.implode(dsod.getColumnNames(), ",") + " FROM " + dsod.getTableName();
		MysqlPreparedStatement mps = new MysqlPreparedStatement(sqlQuery);
		return loadFromQuery(mps, dsod);
	}

	@Override
	public Collection<DataStoreObject> loadBulkDSO(DataStoreObjectDescriptor dsod, LinkedList<Object> keys) throws SQLException {
		StringBuffer sql = new StringBuffer();
		sql.append("SELECT " + StringTools.implode(dsod.getColumnNames(), ",") + " FROM " + dsod.getTableName() + " WHERE " + dsod.getColumnNames()[0] + " IN (");
		int idsLen = keys.size();
		for (int t = 0; t < idsLen; t++) {
			if (t + 1 < idsLen)
				sql.append("?,");
			else
				sql.append("?");
		}
		sql.append(")");
		MysqlPreparedStatement mps = new MysqlPreparedStatement(sql.toString());

		int t = 1;
		for (Object o : keys) {
			dsod.getPrimaryKey().getType().toMps(mps, t, o);
			t++;
		}
		return loadFromQuery(mps, dsod);

	}

	protected String cacheKey(DataStoreObjectDescriptor dsod, Object key) {
		return dsod.getCacheKey() + dsod.getPrimaryKey().getType().toCacheKeyString(key);
	}

	@Override
	public DataStoreObject loadDSO(DataStoreObjectDescriptor dsod, Object key) throws SQLException {

		DataStoreObject dso;
		try {
			dso = dsod.newDsoInstance();
		} catch (InstantiationException e) {
			Log.fatal(e);
			return null;
		} catch (IllegalAccessException e) {
			Log.fatal(e);
			return null;
		}

		byte[] cachedObj = cacheService.getByteArray(cacheKey(dsod, key));
		if (cachedObj != null) {
			dso.fromBytes(new ByteArray(cachedObj));
			return dso;
		}

		String sql = "SELECT " + StringTools.implode(dso.getColumnNames(), ",") + " FROM " + dso.getTableName() + " WHERE " + dso.getColumnNames()[0] + " = ?";
		Log.trace(sql);
		MysqlPreparedStatement mps = new MysqlPreparedStatement(sql);
		dsod.getPrimaryKey().getType().toMps(mps, 1, key);

		ResultTable rt = mysqlService.select(mps);
		if (rt.rowCount() != 1) {
			Log.info("DataStoreService.getDSO() looked for an ID that didn't exist in the database: " + dso.getClass().getName() + "[" + key + "]");
			return null;
		}
		dso.loadFromResultRow(rt.iterator().next());

		cacheService.add(cacheKey(dsod, dso.getPrimaryKey()), dso.toBytes().getBytes());

		return dso;
	}

	/**
	 * When a DSO is updated in the database, call this to refresh the cache.
	 * 
	 * @param dso
	 */
	public void forget(DataStoreObject dso) {
		cacheService.remove(cacheKey(dso.getDataStoreObjectDescriptor(), dso.getPrimaryKey()));
	}

	@Override
	public void save(Collection<DataStoreObject> dsoList) throws SQLException {
		// we could have a mix of DSO's, so let's put them in batches by table.
	
		HashMap<DataStoreObjectDescriptor, LinkedList<DataStoreObject>> dsodMap = new HashMap<DataStoreObjectDescriptor, LinkedList<DataStoreObject>>();
		for(DataStoreObject dso : dsoList) {
			LinkedList<DataStoreObject> mapDsoList = dsodMap.get(dso.getDataStoreObjectDescriptor());
			if (mapDsoList == null) {
				mapDsoList = new LinkedList<DataStoreObject>();
				dsodMap.put(dso.getDataStoreObjectDescriptor(), mapDsoList);
			}
			mapDsoList.add(dso);
		}
		
		/* batch inserts are faster when running in a transaction, but transactions also add some overhead. So 5 is an arbitrary threshold to keep single and small insert batches fast (transaction-less), while large batches will get a speed boost from being in a transaction */
		
		MysqlTransactionHandle mth = null;
		if (dsoList.size() > 5) {
			mth = mysqlService.beginTransaction();
		}

		
		// now process each dsod group

		
		
		
		for (DataStoreObjectDescriptor dsod : dsodMap.keySet()) {
			if (dsod.getPrimaryKey().isAutoIncrement()) {
				
			}
			
			// "INSERT INTO table SET a=1, b=2, c=3 ON DUPLICATE KEY UPDATE b=2, c=3 WHERE a=1";
			
			LinkedList<String> colList = new LinkedList<String>();
			for (int i=0;i<dsod.getColumnCount();i++) {
				colList.add(dsod.getColumnNames()[i] + "=?");  
			}
			
			LinkedList<String> colNoKeyList = new LinkedList<String>();
			for (int i=0;i<dsod.getColumnCount();i++) {
				if (!dsod.getPrimaryKey().getColumnName().equals(dsod.getColumnNames()[i])) {
					colNoKeyList.add(dsod.getColumnNames()[i] + "=?");
				}
			}
			
			
			String sql = "INSERT INTO "+dsod.getTableName()+" SET "+StringTools.implode(colList, ", ") + " ON DUPLICATE KEY UPDATE SET "+StringTools.implode(colNoKeyList, ", ")+ ";";
 			MysqlPreparedStatement mps = new MysqlPreparedStatement(sql);
			
 			for (DataStoreObject dso : dsodMap.get(dsod)) {
 				for (int i=0;i<dsod.getColumnCount();i++) {
 					dsod.getColumnTypes()[i].toMps(mps, i+1, dso.getObject(i));
 				}
 				for (int i=0;i<dsod.getColumnCount();i++) {
 					dsod.getColumnTypes()[i+1].toMps(mps, i+dsod.getColumnCount(), dso.getObject(i+1));
 				}
 				mps.addBatch();
 			}
 			
 			try {
				mysqlService.insert(mps);
			} catch (SQLException e) {
				if (mth != null) mth.rollback();
			}
		}
		
		if (mth != null) mth.commit();
	}
}