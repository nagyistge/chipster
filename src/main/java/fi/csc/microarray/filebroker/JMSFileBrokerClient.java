package fi.csc.microarray.filebroker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;

import javax.jms.JMSException;

import org.apache.log4j.Logger;

import fi.csc.microarray.config.DirectoryLayout;
import fi.csc.microarray.filebroker.FileBrokerClient;
import fi.csc.microarray.filebroker.FileBrokerException;
import fi.csc.microarray.filebroker.NotEnoughDiskSpaceException;
import fi.csc.microarray.messaging.BooleanMessageListener;
import fi.csc.microarray.messaging.MessagingTopic;
import fi.csc.microarray.messaging.ReplyMessageListener;
import fi.csc.microarray.messaging.UrlListMessageListener;
import fi.csc.microarray.messaging.UrlMessageListener;
import fi.csc.microarray.messaging.message.CommandMessage;
import fi.csc.microarray.messaging.message.ParameterMessage;
import fi.csc.microarray.security.CryptoKey;
import fi.csc.microarray.util.Files;
import fi.csc.microarray.util.IOUtils;
import fi.csc.microarray.util.IOUtils.CopyProgressListener;
import fi.csc.microarray.util.Strings;
import fi.csc.microarray.util.UrlTransferUtil;

/**
 * Client interface for the file broker. Used by client and computing service or
 * anyone who needs transfer files within Chipster. 
 * 
 * Mostly used along the PayloadMessages which carry the URLs for the files.
 * 
 * The checkFile(URL url) method is only meant to be used to check if the cached
 * copy of a file is still available at the file broker. This method should not 
 * be used to make decisions of whether a file should be updated at the file broker.
 * In other words, the user of this class should first figure out if a file has been
 * locally modified and needs to be updated. If so, the addFile should be used 
 * directly. The checkFile() should be used if the local file has not been modified,
 * to figure out if a copy of the unmodified file still exists at the broker or should
 * a new copy of an unmodified file be added with addFile(). 
 * 
 * @author hupponen
 *
 */
public class JMSFileBrokerClient implements FileBrokerClient {
	
	private static final int SPACE_REQUEST_TIMEOUT = 300; // seconds
	private static final int QUICK_POLL_OPERATION_TIMEOUT = 5; // seconds 
	private static final int MOVE_FROM_CACHE_TO_STORAGE_TIMEOUT = 24; // hours 
	
	private static final Logger logger = Logger.getLogger(JMSFileBrokerClient.class);
	
	
	private MessagingTopic filebrokerTopic;	
	private boolean useChunked;
	private boolean useCompression;
	private String localFilebrokerPath;
	
	public JMSFileBrokerClient(MessagingTopic urlTopic, String localFilebrokerPath) throws JMSException {

		this.filebrokerTopic = urlTopic;
		this.localFilebrokerPath = localFilebrokerPath;
		
		// Read configs
		this.useChunked = DirectoryLayout.getInstance().getConfiguration().getBoolean("messaging", "use-chunked-http"); 
		this.useCompression = DirectoryLayout.getInstance().getConfiguration().getBoolean("messaging", "use-compression");
		
	}
	
	public JMSFileBrokerClient(MessagingTopic urlTopic) throws JMSException {
		this(urlTopic, null);
	}

	@Override
	public URL addSessionFile() throws JMSException, FileBrokerException {
		// quota checks are not needed for session files (metadata xml file)
		
		// return new url
		URL url = getNewURL(CryptoKey.generateRandom(), useCompression, FileBrokerArea.STORAGE, 1024*1024); // assume 1 MB is enough for all session files
		if (url == null) {
			throw new FileBrokerException("filebroker is not responding");
		}
		
		return url;
	}

	/**
	 * Add file to file broker. Must be a cached file, for other types, use other versions of this method.
	 * 
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#addFile(File, CopyProgressListener)
	 */
	@Override
	public void addFile(String dataId, FileBrokerArea area, File file, CopyProgressListener progressListener) throws FileBrokerException, JMSException, IOException {
		
		if (area != FileBrokerArea.CACHE) {
			throw new UnsupportedOperationException();
		}
		
		if (file.length() > 0 && !this.requestDiskSpace(file.length())) {
			throw new NotEnoughDiskSpaceException();
		}
		
		// get new url
		URL url = getNewURL(dataId, useCompression, FileBrokerArea.CACHE, file.length());
		if (url == null) {
			throw new FileBrokerException("filebroker is not responding");
		}

		// try to move/copy it locally, or otherwise upload the file
		if (localFilebrokerPath != null && !useCompression) {
			String filename = dataId;
			File dest = new File(localFilebrokerPath, filename);
			boolean success = file.renameTo(dest);
			if (!success) {
				IOUtils.copy(file, dest); // could not move (different partition etc.), do a local copy
			}

		} else {
			InputStream stream = new FileInputStream(file);
			try {
				UrlTransferUtil.uploadStream(url, stream, useChunked, useCompression, progressListener);
			} finally {
				IOUtils.closeIfPossible(stream);
			}
		}
	}



	
	/**
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#addFile(InputStream, CopyProgressListener)
	 */
	@Override
	public void addFile(String dataId, FileBrokerArea area, InputStream file, long contentLength, CopyProgressListener progressListener) throws FileBrokerException, JMSException, IOException {
		
		URL url;
		if (area == FileBrokerArea.CACHE) {
			if (contentLength > 0  && !this.requestDiskSpace(contentLength)) {
				throw new NotEnoughDiskSpaceException();
			}

			// Get new url
			url = getNewURL(dataId, useCompression, FileBrokerArea.CACHE, contentLength);
			if (url == null) {
				throw new FileBrokerException("New URL is null.");
			}
			
		} else {
			// Get new url
			url = getNewURL(dataId, useCompression, FileBrokerArea.STORAGE, contentLength);
			if (url == null) {
				throw new FileBrokerException("New URL is null.");
			}
			
		}

		// Upload the stream into a file at filebroker
		logger.debug("uploading new file: " + url);
		UrlTransferUtil.uploadStream(url, file, useChunked, useCompression, progressListener);
		logger.debug("successfully uploaded: " + url);
	}

	
	
	@Override
	public InputStream getFile(String dataId) throws IOException, JMSException {
		URL url = getURL(dataId);
		
		if (url == null) {
			throw new FileNotFoundException("file not found: " + dataId);
		}
		
		InputStream payload = null;
		
		// open stream
		payload = url.openStream();

		// wait for payload to become available
		long waitStartTime = System.currentTimeMillis();
		int waitTime = 10;
		while (payload.available() < 1 && (waitStartTime + QUICK_POLL_OPERATION_TIMEOUT*1000 > System.currentTimeMillis())) {
			// sleep
			try {
				Thread.sleep(waitTime);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			waitTime = waitTime*2;
		}

		// detect compression
		if (url.toString().endsWith(".compressed")) {
			return new InflaterInputStream(payload);
		} else {
			return payload;
		}
		
	}

	
	/**
	 * Get a local copy of a file. If the dataId  matches any of the files found from 
	 * local filebroker paths (given in constructor of this class), then it is symlinked or copied locally.
	 * Otherwise the file pointed by the dataId is downloaded.
	 * @throws JMSException 
	 * 
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#getFile(File, URL)
	 */
	@Override
	public void getFile(String dataId, File destFile) throws IOException, JMSException {
		
		// Try to find the file locally and symlink/copy it
		if (localFilebrokerPath != null) {
			
			// If file in filebroker cache is compressed, it will have specific suffix and we will not match it
			File fileInFilebrokerCache = new File(localFilebrokerPath, dataId);
			
			if (fileInFilebrokerCache.exists()) {
				boolean linkCreated = Files.createSymbolicLink(fileInFilebrokerCache, destFile);
	
				if (!linkCreated) {
					IOUtils.copy(fileInFilebrokerCache, destFile); // cannot create a link, must copy
				}
			}
			
		} else {
			// Not available locally, need to download
			BufferedInputStream inputStream = null;
			BufferedOutputStream fileStream = null;
			try {
				// Download to file
				inputStream = new BufferedInputStream(getFile(dataId));
				fileStream = new BufferedOutputStream(new FileOutputStream(destFile));
				IOUtils.copy(inputStream, fileStream);
				
			} finally {
				IOUtils.closeIfPossible(inputStream);
				IOUtils.closeIfPossible(fileStream);
			}
		}
	}

	@Override
	public boolean isAvailable(String dataId, FileBrokerArea area) throws JMSException {
		BooleanMessageListener replyListener = new BooleanMessageListener();  
		try {
			
			CommandMessage requestMessage = new CommandMessage(CommandMessage.COMMAND_IS_AVAILABLE);
			requestMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID, dataId);
			requestMessage.addNamedParameter(ParameterMessage.PARAMETER_AREA, area.toString());
			filebrokerTopic.sendReplyableMessage(requestMessage, replyListener);
			
			// wait
			Boolean success = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS); 
			
			// check how it went
			
			// timeout
			if (success == null) {
				throw new RuntimeException("timeout while waiting for the filebroker");
			} else {
				return success;
			}
			
		} finally {
			replyListener.cleanUp();
		}
	}

	
	@Override
	public boolean moveFromCacheToStorage(String dataId) throws JMSException {
		logger.debug("moving from cache to storage: " + dataId);
	
		BooleanMessageListener replyListener = new BooleanMessageListener();  
		try {
			
			// ask file broker to move it
			CommandMessage moveRequestMessage = new CommandMessage(CommandMessage.COMMAND_MOVE_FROM_CACHE_TO_STORAGE);
			moveRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID, dataId);
			filebrokerTopic.sendReplyableMessage(moveRequestMessage, replyListener);
			
			// wait
			Boolean success = replyListener.waitForReply(MOVE_FROM_CACHE_TO_STORAGE_TIMEOUT, TimeUnit.HOURS); 
			
			// check how it went
			
			// timeout
			if (success == null) {
				throw new RuntimeException("timeout while waiting for the filebroker");
			} else {
				return success;
			}
			
		} finally {
			replyListener.cleanUp();
		}
	}

	/**
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#checkFile(java.net.URL, long)
	 * FIXME use dataId instead of url
	 */
	@Override
	public boolean checkFile(URL url, long contentLength) {

		HttpURLConnection connection = null;
		try {

			connection = (HttpURLConnection) url.openConnection();
		
			// check file existence
			if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
				// should check content length and checksum
				return true;
			}
		} catch (IOException ioe) {
			return false;	
		} finally {
			IOUtils.disconnectIfPossible(connection);
		}
		return false;
	}

	
	/**
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#getPublicFiles()
	 */
	@Override
	public List<URL> getPublicFiles() throws JMSException {
		return fetchPublicFiles();
	}

	private List<URL> fetchPublicFiles() throws JMSException {

		UrlListMessageListener replyListener = new UrlListMessageListener();  
		List<URL> urlList;
		try {
			CommandMessage fileRequestMessage = new CommandMessage(CommandMessage.COMMAND_PUBLIC_FILES_REQUEST);
			
			filebrokerTopic.sendReplyableMessage(fileRequestMessage, replyListener);
			urlList = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
		} finally {
			replyListener.cleanUp();
		}

		return urlList;
	}

	@Override
	public boolean requestDiskSpace(long size) throws JMSException {

		BooleanMessageListener replyListener = new BooleanMessageListener();  
		Boolean spaceAvailable;
		try {
			CommandMessage spaceRequestMessage = new CommandMessage(CommandMessage.COMMAND_DISK_SPACE_REQUEST);
			spaceRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_DISK_SPACE, String.valueOf(size));
			filebrokerTopic.sendReplyableMessage(spaceRequestMessage, replyListener);
			spaceAvailable = replyListener.waitForReply(SPACE_REQUEST_TIMEOUT, TimeUnit.SECONDS);
		} finally {
			replyListener.cleanUp();
		}

		if (spaceAvailable == null) {
			logger.warn("did not get response for space request");
			return false;
		}
		return spaceAvailable;
		
	}

	@Override
	public void saveRemoteSession(String sessionName, URL sessionURL, LinkedList<String> dataIds) throws JMSException {
		ReplyMessageListener replyListener = new ReplyMessageListener();  
		try {
			CommandMessage storeRequestMessage = new CommandMessage(CommandMessage.COMMAND_STORE_SESSION);
			storeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_SESSION_NAME, sessionName);
			storeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_SESSION_UUID, sessionURL.toExternalForm());
			storeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID_LIST, Strings.delimit(dataIds, "\t"));
			
			filebrokerTopic.sendReplyableMessage(storeRequestMessage, replyListener);
			ParameterMessage reply = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
			
			if (reply == null || !(reply instanceof CommandMessage) || !CommandMessage.COMMAND_FILE_OPERATION_SUCCESSFUL.equals((((CommandMessage)reply).getCommand()))) {
				throw new JMSException("failed to save session metadata remotely");
			}

			
		} finally {
			replyListener.cleanUp();
		}
	}

	@Override
	public String[][] listRemoteSessions() throws JMSException {
		ReplyMessageListener replyListener = new ReplyMessageListener();  
		
		try {
			CommandMessage listRequestMessage = new CommandMessage(CommandMessage.COMMAND_LIST_SESSIONS);
			filebrokerTopic.sendReplyableMessage(listRequestMessage, replyListener);
			ParameterMessage reply = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
			if (reply == null) {
				throw new RuntimeException("server failed to list sessions");
			}
			String[] names, uuids;
			String namesString = reply.getNamedParameter(ParameterMessage.PARAMETER_SESSION_NAME_LIST);
			String uuidsString = reply.getNamedParameter(ParameterMessage.PARAMETER_SESSION_UUID_LIST);
			if (namesString != null && !namesString.equals("") && uuidsString != null && !uuidsString.equals("")) {
				names = namesString.split("\t");
				uuids = uuidsString.split("\t");
				if (names.length != uuids.length) {
					names = new String[0];
					uuids = new String[0];
				}
			} else {
				names = new String[0];
				uuids = new String[0];
			}
			
			return new String[][] {names, uuids};
			
		} finally {
			replyListener.cleanUp();
		}
	}

	@Override
	public void removeRemoteSession(URL sessionURL) throws JMSException {
		ReplyMessageListener replyListener = new ReplyMessageListener();  
		
		try {
			CommandMessage removeRequestMessage = new CommandMessage(CommandMessage.COMMAND_REMOVE_SESSION);
			removeRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_SESSION_UUID, sessionURL.toExternalForm()); 
			filebrokerTopic.sendReplyableMessage(removeRequestMessage, replyListener);
			ParameterMessage reply = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
			
			if (reply == null || !(reply instanceof CommandMessage) || !CommandMessage.COMMAND_FILE_OPERATION_SUCCESSFUL.equals((((CommandMessage)reply).getCommand()))) {
				throw new JMSException("failed to remove session");
			}
			
		} finally {
			replyListener.cleanUp();
		}
	}
	
	/**
	 * Get an URL for a new file from the file broker.
	 * 
	 * Talks to the file broker using JMS.
	 * 
	 * If useCompression is true, request an url ending with .compressed.
	 * NOTE! Compression does not work with files larger than 4 gigabytes
	 * in JDK 1.6 and earlier.
	 *  
	 * @return the new URL, may be null if file broker sends null or
	 * if reply is not received before timeout
	 * 
	 * @throws JMSException
	 */
	private URL getNewURL(String dataId, boolean useCompression, FileBrokerArea area, long contentLength) throws JMSException {
		logger.debug("getting new url");
	
		UrlMessageListener replyListener = new UrlMessageListener();  
		URL url;
		try {
			CommandMessage urlRequestMessage = new CommandMessage(CommandMessage.COMMAND_NEW_URL_REQUEST);
			urlRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID, dataId);
			urlRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_AREA, area.toString());
			urlRequestMessage.addNamedParameter(ParameterMessage.PARAMETER_DISK_SPACE, Long.toString(contentLength));
	
			if (useCompression) {
				urlRequestMessage.addParameter(ParameterMessage.PARAMETER_USE_COMPRESSION);
			}
			filebrokerTopic.sendReplyableMessage(urlRequestMessage, replyListener);
			url = replyListener.waitForReply(SPACE_REQUEST_TIMEOUT, TimeUnit.SECONDS);
		} finally {
			replyListener.cleanUp();
		}
		logger.debug("new url is: " + url);
	
		return url;
	}

	private URL getURL(String dataId) throws JMSException {
		
		UrlMessageListener replyListener = new UrlMessageListener();  
		URL url;
		try {
			CommandMessage getURLMessage = new CommandMessage(CommandMessage.COMMAND_GET_URL);
			getURLMessage.addNamedParameter(ParameterMessage.PARAMETER_FILE_ID, dataId);
	
			filebrokerTopic.sendReplyableMessage(getURLMessage, replyListener);
			url = replyListener.waitForReply(QUICK_POLL_OPERATION_TIMEOUT, TimeUnit.SECONDS);
		} finally {
			replyListener.cleanUp();
		}
	
		return url;
	}
}
