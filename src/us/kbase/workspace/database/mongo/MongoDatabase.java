package us.kbase.workspace.database.mongo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jongo.FindAndModify;
import org.jongo.Jongo;
import org.jongo.MongoCollection;
import org.jongo.marshall.MarshallingException;

import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.database.Database;
import us.kbase.workspace.database.exceptions.DBAuthorizationException;
import us.kbase.workspace.database.exceptions.CorruptWorkspaceDBException;
import us.kbase.workspace.database.exceptions.InvalidHostException;
import us.kbase.workspace.database.exceptions.NoSuchObjectException;
import us.kbase.workspace.database.exceptions.NoSuchWorkspaceException;
import us.kbase.workspace.database.exceptions.PreExistingWorkspaceException;
import us.kbase.workspace.database.exceptions.UninitializedWorkspaceDBException;
import us.kbase.workspace.database.exceptions.WorkspaceCommunicationException;
import us.kbase.workspace.database.exceptions.WorkspaceDBException;
import us.kbase.workspace.workspaces.ObjectIdentifier;
import us.kbase.workspace.workspaces.ObjectMetaData;
import us.kbase.workspace.workspaces.Permission;
import us.kbase.workspace.workspaces.WorkspaceIdentifier;
import us.kbase.workspace.workspaces.WorkspaceMetaData;
import us.kbase.workspace.workspaces.WorkspaceObject;
import us.kbase.workspace.workspaces.WorkspaceObjectCollection;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoException;

public class MongoDatabase implements Database {

	//TODO handle deleting workspaces - changes most methods
	//TODO handle hidden and deleted objects - changes most methods
	
	private static final String SETTINGS = "settings";
	private static final String WORKSPACES = "workspaces";
	private static final String WS_ACLS = "workspaceACLs";
	private static final String WORKSPACE_PTRS = "workspacePointers";
	private String allUsers = "*";
	
	private static MongoClient mongoClient = null;
	private final DB wsmongo;
	private final Jongo wsjongo;
	private final BlobStore blob;
	private final FindAndModify updateWScounter;
	
	private static final Map<String, Map<List<String>, List<String>>> indexes;
	static {
		//hardcoded indexes
		indexes = new HashMap<String, Map<List<String>, List<String>>>();
		Map<List<String>, List<String>> ws = new HashMap<List<String>, List<String>>();
		//find workspaces you own
		ws.put(Arrays.asList("owner"), Arrays.asList(""));
		//find workspaces by permanent id
		ws.put(Arrays.asList("id"), Arrays.asList("unique"));
		//find world readable workspaces
		ws.put(Arrays.asList("globalread"), Arrays.asList("sparse"));
		//find workspaces by mutable name
		ws.put(Arrays.asList("name"), Arrays.asList("unique", "sparse"));
		indexes.put(WORKSPACES, ws);
		Map<List<String>, List<String>> wsACL = new HashMap<List<String>, List<String>>();
		//get a user's permission for a workspace, index covers queries
		wsACL.put(Arrays.asList("id", "user", "perm"), Arrays.asList("unique"));
		//find workspaces to which a user has some level of permission, index coves queries
		wsACL.put(Arrays.asList("user", "perm", "id"), Arrays.asList(""));
		indexes.put(WS_ACLS, wsACL);
		Map<List<String>, List<String>> wsPtr = new HashMap<List<String>, List<String>>();
		//find objects by workspace id & name
		wsPtr.put(Arrays.asList("workspace", "name"), Arrays.asList("unique", "sparse"));
		//find object by workspace id & object id
		wsPtr.put(Arrays.asList("workspace", "id"), Arrays.asList("unique"));
		//find objects by legacy UUID
		wsPtr.put(Arrays.asList("legacyUUID"), Arrays.asList("unique", "sparse"));
		//determine whether a particular object references this object
		wsPtr.put(Arrays.asList("versions.reffedBy"), Arrays.asList(""));
		indexes.put(WORKSPACE_PTRS, wsPtr);
	}

	public MongoDatabase(String host, String database, String backendSecret)
			throws UnknownHostException, IOException, InvalidHostException,
			WorkspaceDBException {
		wsmongo = getDB(host, database);
		try {
			wsmongo.getCollectionNames();
		} catch (MongoException.Network men) {
			throw (IOException) men.getCause();
		}
		wsjongo = new Jongo(wsmongo);
		blob = setupDB(backendSecret);
		updateWScounter = buildCounterQuery();
		ensureIndexes();
	}

	public MongoDatabase(String host, String database, String backendSecret,
			String user, String password) throws UnknownHostException,
			IOException, DBAuthorizationException, InvalidHostException,
			WorkspaceDBException {
		wsmongo = getDB(host, database);
		try {
			wsmongo.authenticate(user, password.toCharArray());
		} catch (MongoException.Network men) {
			throw (IOException) men.getCause();
		}
		try {
			wsmongo.getCollectionNames();
		} catch (MongoException me) {
			throw new DBAuthorizationException("Not authorized for database "
					+ database, me);
		}
		wsjongo = new Jongo(wsmongo);
		blob = setupDB(backendSecret);
		updateWScounter = buildCounterQuery();
		ensureIndexes();
	}
	
	private void ensureIndexes() {
		for (String col: indexes.keySet()) {
			for (List<String> idx: indexes.get(col).keySet()) {
				DBObject index = new BasicDBObject();
				DBObject opts = new BasicDBObject();
				for (String field: idx) {
					index.put(field, 1);
				}
				for (String option: indexes.get(col).get(idx)) {
					if (!option.equals("")) {
						opts.put(option, 1);
					}
				}
				wsmongo.getCollection(col).ensureIndex(index, opts);
			}
		}
	}
	
	private FindAndModify buildCounterQuery() {
		return wsjongo.getCollection("workspaceCounter")
				.findAndModify("{id: 'wscounter'}")
				.upsert().returnNew().with("{$inc: {num: 1}}")
				.projection("{num: 1, _id: 0}");
	}

	private DB getDB(String host, String database) throws UnknownHostException,
			InvalidHostException {
		//Only make one instance of MongoClient per JVM per mongo docs
		if (mongoClient == null) {
			// Don't print to stderr
			Logger.getLogger("com.mongodb").setLevel(Level.OFF);
			final MongoClientOptions opts = MongoClientOptions.builder()
					.autoConnectRetry(true).build();
			try {
				mongoClient = new MongoClient(host, opts);
			} catch (NumberFormatException nfe) {
				throw new InvalidHostException(host
						+ " is not a valid mongodb host");
			}
		}
		return mongoClient.getDB(database);
	}

	private BlobStore setupDB(String backendSecret) throws WorkspaceDBException {
		if (!wsmongo.collectionExists(SETTINGS)) {
			throw new UninitializedWorkspaceDBException(
					"No settings collection exists");
		}
		MongoCollection settings = wsjongo.getCollection(SETTINGS);
		if (settings.count() != 1) {
			throw new CorruptWorkspaceDBException(
					"More than one settings document exists");
		}
		Settings wsSettings = null;
		try {
			wsSettings = settings.findOne().as(Settings.class);
		} catch (MarshallingException me) {
			Throwable ex = me.getCause();
			if (ex == null) {
				throw new CorruptWorkspaceDBException(
						"Unable to unmarshal settings document", me);
			}
			ex = ex.getCause();
			if (ex == null || !(ex instanceof CorruptWorkspaceDBException)) {
				throw new CorruptWorkspaceDBException(
						"Unable to unmarshal settings document", me);
			}
			throw (CorruptWorkspaceDBException) ex;
		}
		if (wsSettings.isGridFSBackend()) {
			return new GridFSBackend(wsmongo);
		}
		if (wsSettings.isShockBackend()) {
			URL shockurl = null;
			try {
				shockurl = new URL(wsSettings.getShockUrl());
			} catch (MalformedURLException mue) {
				throw new CorruptWorkspaceDBException(
						"Settings has bad shock url: "
								+ wsSettings.getShockUrl(), mue);
			}
			BlobStore bs = new ShockBackend(shockurl,
					wsSettings.getShockUser(), backendSecret);
			// TODO if shock, check a few random nodes to make sure they match
			// the internal representation, die otherwise
			return bs;
		}
		throw new RuntimeException("Something's real broke y'all");
	}

	@Override
	public String getBackendType() {
		return blob.getStoreType();
	}
	
	private void checkUser(String user) {
		if (allUsers.equals(user)) {
			throw new IllegalArgumentException("Illegal user name: " + user);
		}
	}

	@Override
	public WorkspaceMetaData createWorkspace(String user, String wsname,
			boolean globalRead, String description) throws
			PreExistingWorkspaceException, WorkspaceCommunicationException {
		checkUser(user);
		//avoid incrementing the counter if we don't have to
		try {
			if (wsjongo.getCollection(WORKSPACES).count("{name: #}", wsname) > 0) {
				throw new PreExistingWorkspaceException(String.format(
						"Workspace %s already exists", wsname));
			}
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		Integer count; 
		try {
			count = (Integer) updateWScounter.as(DBObject.class).get("num");
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		final DBObject ws = new BasicDBObject();
		ws.put("owner", user);
		ws.put("id", count);
		ws.put("globalread", globalRead);
		Date moddate = new Date();
		ws.put("moddate", moddate);
		ws.put("name", wsname);
		ws.put("deleted", null);
		ws.put("numpointers", 0);
		ws.put("description", description);
		try { //this is almost impossible to test and will probably almost never happen
			wsmongo.getCollection(WORKSPACES).insert(ws);
		} catch (MongoException.DuplicateKey mdk) {
			throw new PreExistingWorkspaceException(String.format(
					"Workspace %s already exists", wsname));
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		try {
			setPermissions(count, Arrays.asList(user), Permission.OWNER, false);
			if (globalRead) {
				setPermissions(count, Arrays.asList(allUsers), Permission.READ, false);
			}
		} catch (NoSuchWorkspaceException nswe) { //should never happen
			throw new RuntimeException("just created a workspace that doesn't exist", nswe);
		}
		return new MongoWSMeta(count, wsname, user, moddate, Permission.OWNER,
				globalRead);
	}

	private Map<String, Object> queryWorkspace(WorkspaceIdentifier wsi,
			List<String> fields) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		if (wsi.getId() != null) {
			return queryWorkspace(wsi.getId(), fields);
		}
		return queryWorkspace(wsi.getName(), fields);
	}
	
	private Map<String, Object> queryWorkspace(String wsname, List<String> fields)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		return queryWorkspace(String.format("{name: \"%s\"}", wsname),
				"name " + wsname, fields);
	}
	
	private Map<String, Object> queryWorkspace(int wsid, List<String> fields)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		return queryWorkspace(String.format("{id: %d}", wsid), "id " + wsid,
				fields);
	}
		
	private Map<String, Object> queryWorkspace(String query, String error,
			List<String> fields) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		final DBObject projection = new BasicDBObject();
		for (String field: fields) {
			projection.put(field, 1);
		}
		Map<String, Object> result;
		try {
			@SuppressWarnings("unchecked")
			final Map<String, Object> res = wsjongo.getCollection(WORKSPACES)
					.findOne(query).projection(projection.toString())
					.as(Map.class);
			result = res;
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		if (result == null) {
			throw new NoSuchWorkspaceException(String.format(
					"No workspace with %s exists", error));
		}
		return result;
	}

	@Override
	public String getWorkspaceDescription(WorkspaceIdentifier wsi) throws
			NoSuchWorkspaceException, WorkspaceCommunicationException {
		return (String) queryWorkspace(wsi, Arrays.asList("description"))
				.get("description");
	}
	
	private int getWorkspaceID(WorkspaceIdentifier wsi, boolean verify) throws
			NoSuchWorkspaceException, WorkspaceCommunicationException {
		if (!verify && wsi.getId() != null) {
			return wsi.getId();
		}
		return (int) queryWorkspace(wsi, Arrays.asList("id")).get("id");
	}

	private String getOwner(int wsid) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		return (String) queryWorkspace(wsid, Arrays.asList("owner")).get("owner");
	}
	
	@Override
	public void setPermissions(WorkspaceIdentifier wsi, List<String> users,
			Permission perm) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		for (String user: users) {
			checkUser(user);
		}
		setPermissions(getWorkspaceID(wsi, true), users, perm, true);
	}
	
	private void setPermissions(int wsid, List<String> users, Permission perm,
			boolean checkowner) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		final String owner = checkowner ? getOwner(wsid) : "";
		for (String user: users) {
			if (owner.equals(user)) {
				continue; // can't change owner permissions
			}
			try {
				if (perm.equals(Permission.NONE)) {
					wsjongo.getCollection(WS_ACLS).remove("{id: #, user: #}", wsid, user);
				} else {
					wsjongo.getCollection(WS_ACLS).update("{id: #, user: #}", wsid, user)
						.upsert().with("{$set: {perm: #}}", perm.getPermission());
				}
			} catch (MongoException me) {
				throw new WorkspaceCommunicationException(
						"There was a problem communicating with the database", me);
			}
		}
	}
	
	private Permission translatePermission(int perm) {
		switch (perm) {
			case 0: return Permission.NONE;
			case 1: return Permission.READ;
			case 2: return Permission.WRITE;
			case 3: return Permission.ADMIN;
			case 4: return Permission.OWNER;
			default: throw new IllegalArgumentException(
					"No such permission level " + perm);
		}
	}
	
	private Map<String, Permission> queryPermissions(WorkspaceIdentifier wsi)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		return queryPermissions(wsi, null);
	}
	
	private Map<String, Permission> queryPermissions(WorkspaceIdentifier wsi,
			List<String> users) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		final DBObject query = new BasicDBObject();
		query.put("id", getWorkspaceID(wsi, true));
		if (users != null && users.size() > 0) {
			final DBObject usersdb = new BasicDBObject();
			usersdb.put("$in", users);
			query.put("user", usersdb);
		}
		@SuppressWarnings("rawtypes")
		final Iterable<Map> res;
		try {
			@SuppressWarnings("rawtypes")
			final Iterable<Map> result = wsjongo.getCollection(WS_ACLS)
					.find(query.toString()).projection("{user: 1, perm: 1}")
					.as(Map.class);
			res = result;
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		final Map<String, Permission> ret = new HashMap<String, Permission>();
		for (@SuppressWarnings("rawtypes") Map m: res) {
			ret.put((String) m.get("user"), translatePermission((int) m.get("perm")));
		}
		return ret;
	}

	@Override
	public Permission getPermission(String user, WorkspaceIdentifier wsi)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		checkUser(user);
		final Map<String, Permission> res = getUserAndGlobalPermission(user, wsi);
		Permission perm = Permission.NONE;
		if (res.containsKey(allUsers)) {
			perm = res.get(allUsers); //if allUsers is in the DB it's always read
		}
		if (res.containsKey(user) && !res.get(user).equals(Permission.NONE)) {
			perm = res.get(user);
		}
		return perm;
	}
	
	@Override
	public Map<String, Permission> getUserAndGlobalPermission(
			String user, WorkspaceIdentifier wsi) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		final List<String> users = new ArrayList<String>();
		users.add(allUsers);
		if (user != null) {
			checkUser(user);
			users.add(user);
		}
		final Map<String, Permission> ret = queryPermissions(wsi, users);
		if (!ret.containsKey(user)) {
			ret.put(user, Permission.NONE);
		}
		return ret;
	}

	@Override
	public Map<String, Permission> getAllPermissions(
			WorkspaceIdentifier wsi) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		return queryPermissions(wsi);
	}

	@Override
	public WorkspaceMetaData getWorkspaceMetadata(String user,
			WorkspaceIdentifier wsi) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		final Map<String, Object> ws = queryWorkspace(wsi, Arrays.asList(
				"id", "name", "owner", "moddate"));
		final Map<String, Permission> res = getUserAndGlobalPermission(user, wsi);
		return new MongoWSMeta((int) ws.get("id"), (String) ws.get("name"),
				(String) ws.get("owner"), (Date) ws.get("moddate"),
				res.get(user), res.containsKey(allUsers));
	}

	@Override
	public void setAllUsersSymbol(String allUsers) {
		this.allUsers = allUsers;
	}
	
	private Map<ObjectIdentifier, Integer> getObjectIDs(int workspaceId,
			Set<ObjectIdentifier> objects, boolean verify) throws
			WorkspaceCommunicationException {
		final Map<String, ObjectIdentifier> names
				= new HashMap<String, ObjectIdentifier>();
		final Map<Integer, ObjectIdentifier> ids
				= new HashMap<Integer, ObjectIdentifier>();
		final Map<ObjectIdentifier, Integer> goodIds =
				new HashMap<ObjectIdentifier, Integer>();
		for (final ObjectIdentifier o: objects) {
			if (o.getId() == null) {
				names.put(o.getName(), o);
			} else {
				ids.put(o.getId(), o);
			}
		}
		// could try doing an or later, probably doesn't matter
		// could also try and unify all this mostly duplicate code
		if (!names.isEmpty()) {
			final DBObject query = new BasicDBObject();
			query.put("workspace", workspaceId);
			final DBObject namesdb = new BasicDBObject();
			namesdb.put("$in", names.keySet());
			query.put("name", namesdb);
			System.out.println(query);
			@SuppressWarnings("rawtypes")
			Iterable<Map> res; 
			try {
				res = wsjongo.getCollection(WORKSPACE_PTRS)
						.find(query.toString()).projection("{id: 1, name: 1}")
						.as(Map.class);
			} catch (MongoException me) {
				throw new WorkspaceCommunicationException(
						"There was a problem communicating with the database", me);
			}
			for (@SuppressWarnings("rawtypes") Map m: res) {
				final String name = (String) m.get("name");
				final Integer id = (Integer) m.get("id");
				goodIds.put(names.get(name), id);
			}
		}
		if (!ids.isEmpty()) {
			final DBObject query = new BasicDBObject();
			query.put("workspace", workspaceId);
			query.put("id", workspaceId);
			final DBObject idsdb = new BasicDBObject();
			idsdb.put("$in", ids.values());
			query.put("id", idsdb);
			System.out.println(query);
			@SuppressWarnings("rawtypes")
			Iterable<Map> res; 
			try {
				res = wsjongo.getCollection(WORKSPACE_PTRS)
						.find(query.toString()).projection("{id: 1}")
						.as(Map.class);
			} catch (MongoException me) {
				throw new WorkspaceCommunicationException(
						"There was a problem communicating with the database", me);
			}
			for (@SuppressWarnings("rawtypes") Map m: res) {
				final Integer id = (Integer) m.get("id");
				goodIds.put(ids.get(id), id);
			}
		}
		return goodIds;
	}
	
	// save object over preexisting object
	private ObjectMetaData saveObject(final String user, final int wsid,
			final int objectid, final WorkspaceObject obj)
			throws WorkspaceCommunicationException {
		System.out.println("****save prexisting obj called****");
		System.out.println(wsid);
		System.out.println(objectid);
		System.out.println(obj);
		//TODO save data
		//TODO save datainstance
		final int ver;
		try {
			ver = (int) wsjongo.getCollection(WORKSPACE_PTRS)
					.findAndModify("{workspace: #, id: #}", wsid, objectid)
					.returnNew().with("{$inc: {version: 1}}")
					.projection("{version: 1, _id: 0}").as(DBObject.class)
					.get("version");
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		final DBObject query = new BasicDBObject();
		query.put("workspace", wsid);
		query.put("id", objectid);
		final DBObject pointer = new BasicDBObject();
		pointer.put("version", ver);
		pointer.put("createdby", user);
		pointer.put("meta", obj.getUserMeta());
		pointer.put("createDate", new Date());
		pointer.put("reffedBy", new ArrayList<Object>());
		pointer.put("objectId", null); //TODO add objectID
		pointer.put("revert", null);
		final DBObject versions = new BasicDBObject();
		versions.put("versions", pointer);
		final DBObject update = new BasicDBObject();
		update.put("$push", versions);
		wsmongo.getCollection(WORKSPACE_PTRS).update(query, update);
		
		//TODO save object with preexisting name/id
		return null;
	}
	
	//save brand new object
	private ObjectMetaData saveObject(final String user, final int wsid,
			final int objectid, final String name, final WorkspaceObject obj)
			throws WorkspaceCommunicationException {
		System.out.println("****save new obj called****");
		System.out.println(wsid);
		System.out.println(objectid);
		System.out.println(name);
		System.out.println(obj);
		//TODO if name is null, create one
		final DBObject dbo = new BasicDBObject();
		dbo.put("workspace", wsid);
		dbo.put("id", objectid);
		dbo.put("version", 0);
		dbo.put("name", name);
		dbo.put("deleted", null);
		dbo.put("hidden", false);
		dbo.put("versions", new ArrayList<Object>());
		try {
			wsmongo.getCollection(WORKSPACE_PTRS).insert(dbo);
		} catch (MongoException.DuplicateKey dk) {
			//ok, someone must've just this second added this name to an object
			//this should be a rare event
			//TODO deal with rare event here
			System.out.println(dk);
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		saveObject(user, wsid, objectid, obj);
		//TODO save new object with name, if name null assign name
		//TODO return metadata
		return null;
	}
	
	@Override
	public List<ObjectMetaData> saveObjects(final String user, 
			final WorkspaceObjectCollection objects) throws
			NoSuchWorkspaceException, WorkspaceCommunicationException,
			NoSuchObjectException {
		//this method must maintain the order of the objects
		final int wsid = getWorkspaceID(objects.getWorkspaceIdentifier(), true);
		final Set<ObjectIdentifier> names = new HashSet<ObjectIdentifier>();
		final List<ObjectMetaData> ret = new ArrayList<ObjectMetaData>();
		
		int newobjects = 0;
		for (final WorkspaceObject o: objects) {
			if (o.getObjectIdentifier() != null) {
				names.add(o.getObjectIdentifier());
			} else {
				newobjects++;
			}
		}
		final Map<ObjectIdentifier, Integer> objIDs = getObjectIDs(wsid, names,
				true);
		for (ObjectIdentifier o: names) {
			if (!objIDs.containsKey(o)) {
				if (o.getId() != null) {
					throw new NoSuchObjectException(
							"There is no object with id " + o.getId());
				} else {
					newobjects++;
				}
			}
		}
		//TODO check meta size < 16Kb
		//TODO make sure all object and provenance references exist and aren't deleted
		//TODO check types and get subdata
		int lastid;
			try {
				lastid = (int) wsjongo.getCollection(WORKSPACES)
						.findAndModify("{id: #}", wsid)
						.returnNew().with("{$inc: {numpointers: #}}", newobjects)
						.projection("{numpointers: 1, _id: 0}").as(DBObject.class)
						.get("numpointers");
			} catch (MongoException me) {
				throw new WorkspaceCommunicationException(
						"There was a problem communicating with the database", me);
			}
		int newid = lastid - newobjects + 1;
		//todo get counts and numbers
		for (final WorkspaceObject o: objects) {
			ObjectIdentifier oi = o.getObjectIdentifier();
			if (oi == null) {
				ret.add(saveObject(user, wsid, newid++, null, o));
			} else if (oi.getId() != null) {
				ret.add(saveObject(user, wsid, oi.getId(), o));
			} else if (objIDs.get(oi) != null) {
				ret.add(saveObject(user, wsid, objIDs.get(oi), o));
			} else {
				ret.add(saveObject(user, wsid, newid++, oi.getName(), o));
			}
		}
		return ret;
	}
}
