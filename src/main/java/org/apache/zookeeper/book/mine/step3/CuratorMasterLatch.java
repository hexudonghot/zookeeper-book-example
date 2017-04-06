package org.apache.zookeeper.book.mine.step3;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * author zhouwei.guo
 * date 2017/4/6.
 */
public class CuratorMasterLatch implements Closeable, LeaderLatchListener{

	private String myId;
	private CuratorFramework client;
	private final LeaderLatch leaderLatch;

	private final PathChildrenCache workersCache;
	private final PathChildrenCache tasksCache;

	/*
	 * Random variable we use to select a worker to perform a pending task.
	 */
	private Random rand = new Random(System.currentTimeMillis());


	/**
	 * Creates a new Curator client, setting the retry policy to ExponentialBackoffRetry
	 *
	 * @param myId
	 * @param hostPort
	 * @param retry
	 */
	public CuratorMasterLatch(String myId, String hostPort, RetryPolicy retry) {
		this.myId = myId;
		this.client = CuratorFrameworkFactory.newClient(hostPort, retry);
		this.leaderLatch = new LeaderLatch(this.client, "/master", myId);
		this.workersCache = new PathChildrenCache(this.client, "/workers", true);
		this.tasksCache = new PathChildrenCache(this.client, "/tasks", true);
	}

	private void startZK() {
		client.start();
	}

	private void bootstrap() throws Exception {
		client.create().forPath("/workers", new byte[0]);
		client.create().forPath("/assign", new byte[0]);
		client.create().forPath("/tasks", new byte[0]);
		client.create().forPath("/status", new byte[0]);
	}

	private void runForMaster() throws Exception {
		/*
		 * Register listeners
		 */
		client.getCuratorListenable().addListener(masterLitener);
		client.getUnhandledErrorListenable().addListener(errorsListener);

		/*
		 * Start master election
		 */
		leaderLatch.addListener(this);
		leaderLatch.start();
	}

	CuratorListener masterLitener = new CuratorListener() {
		@Override
		public void eventReceived(CuratorFramework client, CuratorEvent event) {
			System.out.println("Event path: " + event.getPath());
			try{
				switch (event.getType()) {
					case CHILDREN:
						System.out.println("CHILDREN");
						if (event.getPath().contains("/assign")) {
							System.out.println("Successfully got a list of assigments: " + event.getChildren().size() + " tasks");

							/*
							 * Delete the assignments of the absent worker
							 */
							for (String task : event.getChildren()) {
								deleteAssignment(event.getPath() + "/" + task);
							}

							/*
							 * Delete the znode representing the absent worker in the assignments
							 */
							deleteAssignment(event.getPath());

							/*
							 * Reassign the tasks
							 */
							assignTasks(event.getChildren());
						}

						break;
					case CREATE:
						System.out.println("CREATE");

						break;
					case DELETE:
						System.out.println("CREATE");

						break;
					case WATCHED:
						System.out.println("CREATE");


						break;
					default:
						System.out.println("Default case: " + event.getType());

				}
			} catch (Exception e) {
				e.printStackTrace();
				try {
					close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}

		}

	};


	UnhandledErrorListener errorsListener = new UnhandledErrorListener() {
		@Override
		public void unhandledError(String message, Throwable e) {

		}
	};

	PathChildrenCacheListener workersCacheListener = new PathChildrenCacheListener() {
		@Override
		public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) {
			if (PathChildrenCacheEvent.Type.CHILD_REMOVED == event.getType()) {
				try {
					getAbsentWorkerTasks(event.getData().getPath().replaceFirst("/workers/", ""));
				} catch (Exception e) {
					System.out.println("Exception when assigning task. " + e.getMessage());
					e.printStackTrace();
				}
			}
		}
	};

	private void getAbsentWorkerTasks(String worker) throws Exception {
		/*
		 * Get assigned tasks
		 */
		client.getChildren().inBackground().forPath("/assign/" + worker);
	}

	private void deleteAssignment(String path) throws Exception {
		System.out.println("Deleting assignment: " + path);
		client.delete().inBackground().forPath(path);
	}

	private void assignTasks(List<String> tasks) throws Exception {
		for (String task : tasks) {
			assignTask(task, client.getData().forPath("/tasks/" + task));
		}
	}

	private void assignTask(String task, byte[] data) throws Exception {
		/*
		 * Choose worker at random.
		 */
		List<ChildData> workersList = workersCache.getCurrentData();
		System.out.println("Assigning task: " + task + ", data: " + new String(data));

		String designatedWorker = workersList.get(rand.nextInt(workersList.size())).getPath().replaceFirst("/workers/", "");

		/*
		 * Assign task to randomly chosen worker
		 */
		String path = "/assign/" + designatedWorker + "/" + task;
		createAssignment(path, data);

	}

	private void createAssignment(String path, byte[] data) throws Exception {
		/*
		 * The default ACL is ZooDefs.Ids#OPEN+ACL_UNSAFE
		 */
		client.create().withMode(CreateMode.PERSISTENT).inBackground().forPath(path, data);

	}

	/**
	 * This is called when the LeaderLatch's state goes from hasLeadership = false to hasLeadership = true.
	 * <p>
	 * Note that it is possible that by the time this method call happens, hasLeadership has fallen back to false.  If
	 * this occurs, you can expect {@link #notLeader()} to also be called.
	 */
	@Override
	public void isLeader() {
		try {
			/*
			 * Start workersCache
			 */
			workersCache.getListenable().addListener(workersCacheListener);
			workersCache.start();


		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * This is called when the LeaderLatch's state goes from hasLeadership = true to hasLeadership = false.
	 * <p>
	 * Note that it is possible that by the time this method call happens, hasLeadership has become true.  If
	 * this occurs, you can expect {@link #isLeader()} to also be called.
	 */
	@Override
	public void notLeader() {
		try {
			close();
		} catch (IOException e) {
			System.out.println("Exception while closing." + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Closes this stream and releases any system resources associated
	 * with it. If the stream is already closed then invoking this
	 * method has no effect.
	 * <p>
	 * <p> As noted in {@link AutoCloseable#close()}, cases where the
	 * close may fail require careful attention. It is strongly advised
	 * to relinquish the underlying resources and to internally
	 * <em>mark</em> the {@code Closeable} as closed, prior to throwing
	 * the {@code IOException}.
	 *
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	public void close() throws IOException {

	}


	public static void main(String[] args) {
		try {
			CuratorMasterLatch master = new CuratorMasterLatch(args[0], args[1], new ExponentialBackoffRetry(1000, 5));
			master.startZK();
			master.bootstrap();
			master.runForMaster();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
