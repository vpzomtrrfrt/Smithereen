package smithereen.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import smithereen.Utils;

public class BackgroundTaskRunner{
	private final ExecutorService executor;

	private static BackgroundTaskRunner instance;
	private static final Logger LOG=LoggerFactory.getLogger(BackgroundTaskRunner.class);

	public static BackgroundTaskRunner getInstance(){
		if(instance==null){
			instance=new BackgroundTaskRunner();
		}
		return instance;
	}

	private BackgroundTaskRunner(){
		executor=Executors.newCachedThreadPool();
	}

	public void submit(Runnable r){
		executor.submit(r);
	}

	public static void shutDown(){
		if(instance==null)
			return;
		LOG.info("Stopping thread pool");
		Utils.stopExecutorBlocking(instance.executor, LOG);
		LOG.info("Stopped");
	}
}
