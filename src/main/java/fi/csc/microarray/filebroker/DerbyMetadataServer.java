package fi.csc.microarray.filebroker;

import it.sauronsoftware.cron4j.Scheduler;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.TimerTask;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import fi.csc.microarray.config.Configuration;
import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;
import fi.csc.microarray.config.DirectoryLayout;

/**
 * Metadata server keeps track of files and sessions that are saved to long term storage space of the file broker.
 * It keeps track of metadata that is related to data files and session files. Runs on top of embedded Derby SQL 
 * database.
 * 
 * @author Aleksi Kallio
 *
 */
public class DerbyMetadataServer {
	/**
	 * Logger for this class
	 */
	private static final Logger logger = Logger.getLogger(DerbyMetadataServer.class);
	
	private static final String METADATA_BACKUP_PREFIX="filebroker-metadata-db-backup-";

	private static final String DEFAULT_EXAMPLE_SESSION_OWNER = "example_session_owner";
	private static final String DEFAULT_EXAMPLE_SESSION_FOLDER = "Example sessions";

	
	private static String[][] SQL_CREATE_TABLES = new String[][] {
		{ 
			"sessions",
			"CREATE TABLE chipster.sessions (" +
					"uuid VARCHAR(200) PRIMARY KEY,  " +
					"name VARCHAR(200),  " +
				"username VARCHAR(200))" 
		},
		{
			"files",
			"CREATE TABLE chipster.files (" +
					"uuid VARCHAR(200) PRIMARY KEY,  " +
					"size BIGINT,  " +
					"created TIMESTAMP,  " +
					"last_accessed TIMESTAMP)"
		},
		{
			"belongs_to",
			"CREATE TABLE chipster.belongs_to (" + 
					"session_uuid VARCHAR(200)," +
					"file_uuid VARCHAR(200))"
		},
		{
			"special_users",
			"CREATE TABLE chipster.special_users (" + 
					"username VARCHAR(200) PRIMARY KEY," + 
					"show_as_folder VARCHAR(200))"
		}
	};

	
	private static String SQL_INSERT_SESSION  = "INSERT INTO chipster.sessions (name, username, uuid) VALUES (?, ?, ?)";
	private static String SQL_SELECT_SESSIONS_BY_USERNAME = "SELECT name, CAST(null AS VARCHAR(200)) as folder, uuid FROM CHIPSTER.SESSIONS WHERE username = ? UNION SELECT name, show_as_folder as folder, uuid FROM CHIPSTER.SESSIONS,  CHIPSTER.SPECIAL_USERS WHERE CHIPSTER.SESSIONS.username = CHIPSTER.SPECIAL_USERS.username";
	private static String SQL_SELECT_SESSIONS_BY_NAME_AND_USERNAME = "SELECT uuid FROM chipster.sessions WHERE name = ? AND username = ?";
	private static String SQL_DELETE_SESSION  = "DELETE FROM chipster.sessions WHERE uuid = ?";
	private static String SQL_UPDATE_SESSION_NAME  = "UPDATE chipster.sessions SET name = ? WHERE uuid = ?";
	
	private static String SQL_INSERT_FILE  = "INSERT INTO chipster.files (uuid, size, created, last_accessed) VALUES (?, ?, ?, ?)";
	private static String SQL_UPDATE_FILE_ACCESSED  = "UPDATE chipster.files SET last_accessed = ? WHERE uuid = ?";
	private static String SQL_SELECT_FILES_TO_BE_ORPHANED  = "SELECT uuid from chipster.files WHERE uuid IN (SELECT file_uuid from chipster.belongs_to WHERE session_uuid = ?) AND uuid NOT IN (SELECT file_uuid from chipster.belongs_to WHERE NOT session_uuid = ?)";
	private static String SQL_DELETE_FILE  = "DELETE FROM chipster.files WHERE uuid = ?";
	
	private static String SQL_INSERT_BELONGS_TO  = "INSERT INTO chipster.belongs_to (session_uuid, file_uuid) VALUES (?, ?)";
	private static String SQL_DELETE_BELONGS_TO  = "DELETE FROM chipster.belongs_to WHERE session_uuid = ?";
	
	private static String SQL_INSERT_SPECIAL_USER  = "INSERT INTO chipster.special_users (username, show_as_folder) VALUES (?, ?)";
	private static String SQL_DELETE_SPECIAL_USER  = "DELETE FROM chipster.special_users WHERE username = ?";
	
	private static String SQL_LIST_STORAGE_USAGE_OF_USERS = "SELECT chipster.sessions.username, SUM(chipster.files.size) as size FROM chipster.sessions JOIN chipster.belongs_to ON chipster.sessions.uuid = chipster.belongs_to.session_uuid JOIN chipster.files ON chipster.files.uuid = chipster.belongs_to.file_uuid GROUP BY chipster.sessions.username";
	private static String SQL_LIST_STORAGE_USAGE_OF_SESSIONS = "SELECT chipster.sessions.username, chipster.sessions.name, SUM(chipster.files.size) AS size , MAX(chipster.files.last_accessed) AS date FROM chipster.sessions JOIN chipster.belongs_to ON chipster.sessions.uuid = chipster.belongs_to.session_uuid  JOIN chipster.files ON chipster.files.uuid = chipster.belongs_to.file_uuid WHERE chipster.sessions.username = ? GROUP BY chipster.sessions.uuid, chipster.sessions.name, chipster.sessions.username";
	private static String SQL_GET_TOTAL_DISK_USAGE = "SELECT SUM(chipster.files.size) AS size FROM chipster.files";

	private static String SQL_BACKUP = "CALL SYSCS_UTIL.SYSCS_BACKUP_DATABASE(?)";
	
	private Connection connection = null;

	/**
	 * Initialises the server. If underlying embedded Derby SQL database is not initialised, it is 
	 * initialised first.
	 * 
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 * @throws IllegalConfigurationException 
	 * @throws IOException 
	 */
	public DerbyMetadataServer() throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException, IOException, IllegalConfigurationException {
		
		// initialise connection
		System.setProperty("derby.system.home", "db-root");
		Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance(); // allows multiple connections in one JVM, but not from multiple JVM's
		String strUrl = "jdbc:derby:ChipsterFilebrokerMetadataDatabase;create=true";
		
		// One way to restore a backup
		// See http://db.apache.org/derby/docs/10.1/adminguide/tadminhubbkup44.html
		//String strUrl = "jdbc:derby:ChipsterFilebrokerMetadataDatabase;restoreFrom=path";
		
		connection = DriverManager.getConnection(strUrl);
		
		// initialise database, if needed
		initialise();

		logger.info("metadata database started");
		
		
		// initialise metadata database backup
		Configuration configuration = DirectoryLayout.getInstance().getConfiguration();
		if (configuration.getBoolean("filebroker", "enable-metadata-backups")) {
		
			// get backup configuration
			File metadataBackupDir = DirectoryLayout.getInstance().getFilebrokerMetadataBackupDir();
			int metadataBackupKeepCount = configuration.getInt("filebroker", "metadata-backup-keep-count");
			String backupTime = configuration.getString("filebroker", "metadata-backup-time").trim();
			
			// schedule backup tasks
			Scheduler scheduler = new Scheduler();
			scheduler.schedule(backupTime, new MetadataBackupTimerTask(metadataBackupDir, metadataBackupKeepCount));
			scheduler.start();
			
			logger.info("metadata backups enabled at: " + backupTime);
		} else {
			logger.info("metadata backups disabled");
		}

		
	}
	
	private void initialise() throws SQLException {

		// create all missing tables
		int tableCount = 0;
		for (int i = 0; i < SQL_CREATE_TABLES.length; i++) {
			
			String table = SQL_CREATE_TABLES[i][0];
		
			ResultSet tables = connection.getMetaData().getTables(null, "CHIPSTER", table.toUpperCase(), new String[] { "TABLE" });
			if (!tables.next()) {
				
				// table does not exist, create it
				String createTable = SQL_CREATE_TABLES[i][1];
				PreparedStatement ps = connection.prepareStatement(createTable);
				ps.execute();
				tableCount++;
				
				// populate table, if needed
				if (table.equals("special_users")) {
					addSpecialUser(DEFAULT_EXAMPLE_SESSION_OWNER, DEFAULT_EXAMPLE_SESSION_FOLDER);
				}
			}
			
		}
		
		// report what was done
		if (tableCount > 0) {
			logger.info("Created " + tableCount + " missing tables to database");
		}
	}

	/**
	 * Lists all sessions that are available to the given user.
	 * 
	 * @param username
	 * @return
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	public List<String>[] listSessions(String username) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_SELECT_SESSIONS_BY_USERNAME);
		ps.setString(1, username);
		ResultSet rs = ps.executeQuery();
		LinkedList<String> names = new LinkedList<String>();
		LinkedList<String> uuids = new LinkedList<String>();
		
		// go through files and add them, creating folders when needed
		HashSet<String> folders = new HashSet<>();
		while (rs.next()) {
			String name = rs.getString("name");
			if (rs.getString("folder") != null) {
				
				// need to make this file show inside a folder
				String folder = rs.getString("folder");
				
				// folder not yet seen, make entry for it first
				if (!folders.contains(folder)) {
					folders.add(folder);
					names.add(folder + "/");
					uuids.add("");
				}
				
				// prefix file name with folder
				name =  folder + "/" + name;
			}
			names.add(name);
			uuids.add(rs.getString("uuid"));
		}

		return new List[] { names, uuids };
	}

	/**
	 * 'Touches' file.
	 * 
	 * @param uuid
	 * @throws SQLException
	 */
	public void markFileAccessed(String uuid) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_UPDATE_FILE_ACCESSED);
		ps.setTimestamp(1, new Timestamp(new Date().getTime()));
		ps.setString(2, uuid);
		ps.execute();
	}

	/**
	 * Adds metadata of a data file to the database.
	 * 
	 * @param uuid unique identifier (filename) of the file
	 * @param size file size in bytes
	 * 
	 * @throws SQLException
	 */
	public void addFile(String uuid, long size) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_INSERT_FILE);
		ps.setString(1, uuid);
		ps.setLong(2, size);
		Timestamp now = new Timestamp(new Date().getTime());
		ps.setTimestamp(3, now);
		ps.setTimestamp(4, now);
		ps.execute();
	}
	
	/**
	 * Removes a data file from the database.
	 * 
	 * @param uuid unique identifier (filename) of the file
	 * @throws SQLException
	 */
	public void removeFile(String uuid) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_DELETE_FILE);
		ps.setString(1, uuid);
		ps.execute();
	}

	/**
	 * Adds special username to the database. All sessions owned by the special
	 * users are visible to everyone. They can be used to create shared
	 * example sessions etc.
	 * 
	 * @param username special username to add
	 * @throws SQLException
	 */
	public void addSpecialUser(String username, String showAsFolder) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_INSERT_SPECIAL_USER);
		ps.setString(1, username);
		ps.setString(2, showAsFolder);
		ps.execute();
	}
	
	/**
	 * Removes a special user from the database.
	 * 
	 * @param uuid special username to remove
	 * @throws SQLException
	 */
	public void removeSpecialUser(String username) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_DELETE_SPECIAL_USER);
		ps.setString(1, username);
		ps.execute();
	}
	
	/**
	 * Links data file to a session (file).
	 * 
	 * @param fileUuid identifier of the data file
	 * @param sessionUuid identifier of the session
	 * 
	 * @throws SQLException
	 */
	public void linkFileToSession(String fileUuid, String sessionUuid) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_INSERT_BELONGS_TO);
		ps.setString(1, sessionUuid);
		ps.setString(2, fileUuid);
		ps.execute();
		
	}
	
	/**
	 * Adds session to the database.
	 * 
	 * @param username owner of the session
	 * @param name human readable name of the session
	 * @param uuid identifier of the session
	 * 
	 * @throws SQLException
	 */
	public void addSession(String username, String name, String uuid) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_INSERT_SESSION);
		ps.setString(1, name);
		ps.setString(2, username);
		ps.setString(3, uuid);
		ps.execute();
	}
	
	public void renameSession(String newName, String uuid) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_UPDATE_SESSION_NAME);
		ps.setString(1, newName);
		ps.setString(2, uuid);
		ps.execute();
	}
	
	public String fetchSession(String username, String name)  throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_SELECT_SESSIONS_BY_NAME_AND_USERNAME);
		ps.setString(1, name);
		ps.setString(2, username);
		ResultSet sessions = ps.executeQuery();
		if (sessions.next()) {
			return sessions.getString(1);
		} else {
			return null;
		}
	}

	/**
	 * Removes session and dependent entries from the database. Dependent entries
	 * include file-session of the removed session and all linked files that are not
	 * linked to any other sessions.
	 * 
	 * @param uuid
	 * @throws SQLException
	 */
	public List<String> removeSession(String uuid) throws SQLException {

		// collect removed files so that they can be removed also physically
		LinkedList<String> removed = new LinkedList<String>();
		
		// find data files that will orphaned (must be done before removing belongs_to)
		PreparedStatement selectPs = connection.prepareStatement(SQL_SELECT_FILES_TO_BE_ORPHANED);
		selectPs.setString(1, uuid);
		selectPs.setString(2, uuid);
		ResultSet uuidRs = selectPs.executeQuery();
		LinkedList<String> orphanUuids = new LinkedList<String>();
		while (uuidRs.next()) {
			orphanUuids.add(uuidRs.getString(1));
		}

		// remove session entry from db 
		// ("entry point" is removed first, so if something fails, broken session entry is not left behind)
		PreparedStatement sessionPs = connection.prepareStatement(SQL_DELETE_SESSION);
		sessionPs.setString(1, uuid);
		sessionPs.execute();

		// remove belongs_to entry from db
		PreparedStatement belongsToPs = connection.prepareStatement(SQL_DELETE_BELONGS_TO);
		belongsToPs.setString(1, uuid);
		belongsToPs.execute();

		// remove session file entry from db and add to list of removed files
		PreparedStatement sessionFilePs = connection.prepareStatement(SQL_DELETE_FILE);
		sessionFilePs.setString(1, uuid);
		sessionFilePs.execute();
		removed.add(uuid);
		
		
		// remove orphaned data file entries from db and add to list of removed files
		for (String orphanUuid : orphanUuids) {
			PreparedStatement dataFilePs = connection.prepareStatement(SQL_DELETE_FILE);
			dataFilePs.setString(1, orphanUuid);
			dataFilePs.execute();
			removed.add(orphanUuid);
		}

		return removed;
	}
	
	/**
	 * Backup the database using the online backup procedure.
	 * 
	 * During the interval the backup is running, the database can be read, but writes to the database are blocked.
	 * 
	 * @param backupDir directory which will contain the db backup
	 * @throws SQLException
	 */
	public void backup(String backupDir) throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_BACKUP);
		ps.setString(1, backupDir.replace(File.separator, "/"));
		ps.execute();
	}
	
	
	/**
	 * TimerTask for running metadata database backup.
	 * 
	 * @author hupponen
	 *
	 */
	private class MetadataBackupTimerTask extends TimerTask {

		private File baseBackupDir;
		private int backupKeepCount;
		
		/**
		 * Backup result will be backupDir/filebroker-metadata-db-backup-yyyy-MM-dd_mm:ss/ChipsterFilebrokerMetadataDatabase
		 * 
		 * @param backupDir base directory for backups, individual backups will be subdirectories 
		 */
		public MetadataBackupTimerTask(File backupDir, int backupKeepCount) {
			this.baseBackupDir = backupDir;
			this.backupKeepCount = backupKeepCount;
		}
		
		
		@Override
		public void run() {
			logger.info("backing up metadata database");
			long startTime = System.currentTimeMillis();
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd_mm:ss");
			
			String fileName = baseBackupDir.getAbsolutePath() + File.separator + METADATA_BACKUP_PREFIX + df.format(new Date());
			try {
				backup(fileName);
			} catch (SQLException e) {
				logger.error("backing up metadata database failed", e);
			}
			logger.info("metadata backup took " + (System.currentTimeMillis() - startTime) + " ms");

			
			// remove old backups
			if (backupKeepCount >= 0) {

				// get backup dirs
				File[] backupDirs = baseBackupDir.listFiles(new FilenameFilter() {

					@Override
					public boolean accept(File dir, String name) {
						if (name.startsWith(METADATA_BACKUP_PREFIX)) {
							return true;
						}
						return false;
					}
				});

				// need to delete old?
				if (backupDirs.length > backupKeepCount) {
					long deleteStartTime = System.currentTimeMillis();
					
					// sort according to file name
					Arrays.sort(backupDirs, new Comparator<File>() {
						@Override
						public int compare(File o1, File o2) {
							return o1.getName().compareTo(o2.getName());
						}
						
					});
	
					// delete oldest until at keep limit
					for (int i = 0; backupDirs.length - i > backupKeepCount; i++) {
						logger.info("deleting old metadata backup: " + backupDirs[i].getName());
						try {
							FileUtils.deleteDirectory(backupDirs[i]);
						} catch (IOException e) {
							logger.error("could not delete old metadata backup directory: " + backupDirs[i]);
						}
					}
					logger.info("deleting old metadata backups took " + (System.currentTimeMillis() - deleteStartTime) + " ms");
				}
			}
		}
	}


	@SuppressWarnings("unchecked")
	public List<String>[] getListStorageusageOfUsers() throws SQLException {
		PreparedStatement ps = connection.prepareStatement(SQL_LIST_STORAGE_USAGE_OF_USERS);

		ResultSet rs = ps.executeQuery();
		LinkedList<String> usernames = new LinkedList<String>();
		LinkedList<String> sizes = new LinkedList<String>();

		while (rs.next()) {
			String username = rs.getString("username");
			String size = rs.getString("size");
			usernames.add(username);
			sizes.add(size);
		}

		return new List[] { usernames, sizes };
	}

	@SuppressWarnings("unchecked")
	public List<String>[] listStorageUsageOfSessions(String username) throws SQLException {
		
		PreparedStatement ps = connection.prepareStatement(SQL_LIST_STORAGE_USAGE_OF_SESSIONS);
		ps.setString(1, username);
		ResultSet rs = ps.executeQuery();
		
		LinkedList<String> usernames = new LinkedList<String>();
		LinkedList<String> sessions = new LinkedList<String>();
		LinkedList<String> sizes = new LinkedList<String>();
		LinkedList<String> dates = new LinkedList<String>();
		
		//FIXME SimpleDateFormat is different in different locales, not good for messaging
		DateFormat dateFormatter = new SimpleDateFormat();
		
		while (rs.next()) {
			String user = rs.getString("username");
			String session = rs.getString("name");
			String size = rs.getString("size");
			Date date = rs.getDate("date");
			usernames.add(user);
			sessions.add(session);
			sizes.add(size);
			dates.add(dateFormatter.format(date));
		}

		return new List[] { usernames, sessions, sizes, dates };
	}
	
	public String getStorageUsageTotals() throws SQLException {
		
		PreparedStatement ps = connection.prepareStatement(SQL_GET_TOTAL_DISK_USAGE);
		ResultSet rs = ps.executeQuery();
		
		String size = rs.getString("size");		

		return size;
	}
}
