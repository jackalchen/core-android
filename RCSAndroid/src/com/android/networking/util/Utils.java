/* ******************************************************
 * Create by : Alberto "Q" Pelliccione
 * Company   : HT srl
 * Project   : AndroidService
 * Created   : 01-dec-2010
 *******************************************************/

package com.android.networking.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;

import android.content.res.AssetManager;

import com.android.networking.Status;
import com.android.networking.auto.Cfg;

// TODO: Auto-generated Javadoc
/**
 * The Class Utils.
 */
public final class Utils {

	/** The debug. */
	private static final String TAG = "Utils"; //$NON-NLS-1$

	private Utils() {
	};

	/**
	 * Sleep.
	 * 
	 * @param t
	 *            the t
	 */
	public static void sleep(final int t) {
		try {
			Thread.sleep(t);
		} catch (final InterruptedException e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			if (Cfg.DEBUG) {
				Check.log(TAG + " sleep() throwed an exception");//$NON-NLS-1$
			}
			if (Cfg.DEBUG) {
				Check.log(e);//$NON-NLS-1$
			}
		}
	}

	/** The rand. */
	static SecureRandom rand = new SecureRandom();

	/**
	 * Gets the unique id.
	 * 
	 * @return the unique id
	 */
	public static long getRandom() {
		return rand.nextLong();
	}

	public static byte[] getRandomByteArray(int sizeMin, int sizeMax) {
		int size = rand.nextInt(sizeMax - sizeMin) + sizeMin;

		byte[] randData = new byte[size];
		rand.nextBytes(randData);
		
		return randData;
	}

	/**
	 * Gets the time stamp.
	 * 
	 * @return the time stamp
	 */
	public static long getTimeStamp() {
		return System.currentTimeMillis();
	}

	


	public static byte[] concat(byte[]... arrays) {
		int size=0;
		for(int i = 0; i < arrays.length; i++){
	        size+=arrays[i].length;
	    }
		
		byte[] result= new byte[size];
		size=0;
		for(int i = 0; i < arrays.length; i++){
			System.arraycopy(arrays[i], 0, result, size, arrays[i].length);
	        size+=arrays[i].length;
	    }
		
		return result;
	}

	public static byte[] getAsset(String asset) {
		try {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (getAsset): " + asset);
			}
			AssetManager assetManager = Status.getAppContext().getResources().getAssets();
			InputStream stream = assetManager.open(asset);
			byte[] ret = ByteArray.inputStreamToBuffer(stream, 0);
			//stream.close();
			
			return ret;
		} catch (IOException e) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (getAsset): " +e);
			}
			return new byte[]{};
		}
	}
}
