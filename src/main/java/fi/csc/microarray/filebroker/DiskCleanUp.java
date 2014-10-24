package fi.csc.microarray.filebroker;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import fi.csc.microarray.util.Files;

public class DiskCleanUp {
	
	private static Logger logger = Logger.getLogger(DiskCleanUp.class);
	
	private File root;
	private int cleanUpTriggerLimitPercentage;
	private int cleanUpTargetPercentage;
	private int cleanUpMinimumFileAge;
	private long minimumSpaceForAcceptUpload;

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private Future<?> lastCleanUp;
	private Object lastCleanUpLock = new Object(); // lock mustn't be null


	public DiskCleanUp(File root, int cleanUpTriggerLimitPercentage, int cleanUpTargetPercentage, int cleanUpMinimumFileAge, long minimumSpaceForAcceptUpload) {
		this.root = root;
		this.cleanUpTriggerLimitPercentage = cleanUpTriggerLimitPercentage;
		this.cleanUpTargetPercentage = cleanUpTargetPercentage;
		this.cleanUpMinimumFileAge = cleanUpMinimumFileAge;
		this.minimumSpaceForAcceptUpload = minimumSpaceForAcceptUpload;		
		
		logger.info("total space: " + FileUtils.byteCountToDisplaySize(root.getTotalSpace()));
		logger.info("usable space: " + FileUtils.byteCountToDisplaySize(root.getUsableSpace()));
		logger.info("minimum space for accepted upload: " + FileUtils.byteCountToDisplaySize(minimumSpaceForAcceptUpload));
		logger.info("cache clean up will start when usable space is less than: " + FileUtils.byteCountToDisplaySize(getCleanUpSoftLimit()));
		logger.info("cache clean target usable space is:  " + FileUtils.byteCountToDisplaySize(getCleanUpTargetUsableSpace()));		
		logger.info("will not clean up files newer than: " + (cleanUpMinimumFileAge/3600) + "h");
	}
	
	public long getCleanUpSoftLimit() {
		
		long usableSpaceSoftLimit = (long) ((double)root.getTotalSpace()*(double)(100-cleanUpTriggerLimitPercentage)/100);				
		return usableSpaceSoftLimit;
	}
	
	public long getCleanUpTargetUsableSpace() {
		return (long) ((double)root.getTotalSpace()*(double)(100-cleanUpTargetPercentage)/100);
	}

	public void scheduleCleanUp(final long requestedSize) {			
		try {
			runIfNotAlreadyRunning(new CleanUpRunnable(requestedSize), false);
		} catch (ExecutionException | InterruptedException e) {
			logger.warn("exception while cleaning cache", e);
		}		
	}
	
	public void cleanUpAndWait(long requestedSize) {
		try {
			runIfNotAlreadyRunning(new CleanUpRunnable(requestedSize), true);
		} catch (ExecutionException | InterruptedException e) {
			logger.warn("exception while cleaning cache", e);
		}
	}

	private void runIfNotAlreadyRunning(Runnable runnable, boolean wait) throws ExecutionException, InterruptedException {

		Future<?> cleanUp = null;
		
		synchronized (lastCleanUpLock) {			
			if (lastCleanUp == null || lastCleanUp.isDone()) {	
				lastCleanUp = executor.submit(runnable);
			} else {
				logger.info("cache cleanup already running, skipping this one");
			}
			cleanUp = lastCleanUp;
		}
		
		if (wait) {
			cleanUp.get();
		}
	}

	public class CleanUpRunnable implements Runnable {
		private long requestedSize;

		public CleanUpRunnable(long requestedSize) {
			this.requestedSize = requestedSize;
		}
		public void run () {
			
			//FIXME
			try {
				Thread.sleep(5*60_000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			//FIXME

			long cleanUpBeginTime = System.currentTimeMillis();
			long cleanUpTargetLimit = getCleanUpTargetUsableSpace();
			logger.info("cache cleanup, target usable space: " + FileUtils.byteCountToDisplaySize(requestedSize + cleanUpTargetLimit) + 
					" (" + FileUtils.byteCountToDisplaySize(requestedSize) + " + " + FileUtils.byteCountToDisplaySize(cleanUpTargetLimit) + 
					", " + (100-cleanUpTargetPercentage) + "%)");
			Files.makeSpaceInDirectory(root, requestedSize + cleanUpTargetLimit, cleanUpMinimumFileAge, TimeUnit.SECONDS);
			logger.info("cache cleanup took " + (System.currentTimeMillis() - cleanUpBeginTime) + " ms, usable space now " + FileUtils.byteCountToDisplaySize(root.getUsableSpace())); 
		}
	}

	/**
	 * Handle space request.
	 * 
	 * This will start a clean up when necessary. On a soft limit, the current
	 * request can be satisfied immediately and the clean up is only scheduled.
	 * If a hard limit is reached and allowWait is true, this will wait until
	 * the clean up is done.
	 * 
	 * @param requestedSize
	 * @param allowWait
	 *            When the space request can't be satisfied immediately, should
	 *            we wait for clean up or just return false.
	 * @return true if requested bytes are available
	 */
	public boolean spaceRequest(long requestedSize, boolean allowWait) {
				
		boolean spaceAvailable;
		
		if (root.getUsableSpace() - requestedSize > getCleanUpSoftLimit()) {
			
			// space available, clean up limit will not be reached
			logger.debug("enough space available, no need to do anything");
			spaceAvailable = true;
			
		} else if (isEnoughSpace(requestedSize)) {

			// will reach soft limit, but not hard limit 
			logger.info("space request: " + FileUtils.byteCountToDisplaySize(requestedSize)
					+ ", usable: " + FileUtils.byteCountToDisplaySize(root.getUsableSpace())
					+ ", usable space soft limit: " + FileUtils.byteCountToDisplaySize(getCleanUpSoftLimit())
					+ " will be reached --> scheduling clean up");

			// schedule clean up
			scheduleCleanUp(requestedSize);
			spaceAvailable = true;

		} else if (getCleanUpTargetUsableSpace() - minimumSpaceForAcceptUpload > requestedSize){

			// there isn't enough space, but waiting for cleanup should help		
			logger.info("space request: " + FileUtils.byteCountToDisplaySize(requestedSize)
					+ " usable: " + FileUtils.byteCountToDisplaySize(root.getUsableSpace())
					+ " clean up target: " + FileUtils.byteCountToDisplaySize(getCleanUpTargetUsableSpace())
					+ ", not enough space yet --> wait for clean up");

			if (allowWait) {
				cleanUpAndWait(requestedSize);
			} else {
				scheduleCleanUp(requestedSize);
				logger.info("waiting isn't allowed");
			}

			logger.info("not accepting upload if less than " + FileUtils.byteCountToDisplaySize(minimumSpaceForAcceptUpload) + " usable space after upload");

			// check if cleaned up enough 
			if (isEnoughSpace(requestedSize)) {
				logger.info("enough space after cleaning");
				spaceAvailable = true;
			} else {
				logger.info("not enough space after cleaning");
				spaceAvailable = false;
			}			

		} else {
			// request more than total, no can do
			logger.info("space request: " + FileUtils.byteCountToDisplaySize(requestedSize) + ", usable: " + FileUtils.byteCountToDisplaySize(root.getUsableSpace()) + 
					", maximum space: " + FileUtils.byteCountToDisplaySize(root.getTotalSpace()) + 
					", minimum usable: " + FileUtils.byteCountToDisplaySize(minimumSpaceForAcceptUpload) + 
					" --> not possible to make enough space");

			spaceAvailable = false;
		}
		
		return spaceAvailable;
	}

	private boolean isEnoughSpace(long requestedSize) {
		return root.getUsableSpace() - requestedSize > minimumSpaceForAcceptUpload;
	}
}
