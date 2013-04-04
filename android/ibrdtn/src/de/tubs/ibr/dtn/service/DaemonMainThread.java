/*
 * DaemonMainThread.java
 * 
 * Copyright (C) 2013 IBR, TU Braunschweig
 *
 * Written-by: Dominik Schürmann <dominik@dominikschuermann.de>
 * 	           Johannes Morgenroth <morgenroth@ibr.cs.tu-bs.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package de.tubs.ibr.dtn.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.provider.Settings.Secure;
import android.util.Log;
import de.tubs.ibr.dtn.DaemonState;
import de.tubs.ibr.dtn.api.SingletonEndpoint;

public class DaemonMainThread extends Thread {
	private final static String TAG = "DaemonMainThread";

	private DaemonService mService;

	public DaemonMainThread(DaemonService context) {
		this.mService = context;
	}

	public void run()
	{
		// lower priority of this thread
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

		String configPath = mService.getFilesDir().getPath() + "/" + "config";

		// create configuration file
		createConfig(mService, configPath);

		// enable debug based on prefs
		boolean debug = false;
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mService);
		int logLevel = Integer.valueOf(preferences.getString("log_options", "0"));
		int debugVerbosity = Integer.valueOf(preferences.getString("pref_debug_verbosity", "0"));

		// TODO: test
		// NativeDaemonWrapper.daemonReload();

		// loads config and initializes daemon
		NativeDaemonWrapper.daemonInitialize(configPath, logLevel, debugVerbosity);

		// broadcast online state
		// TODO: better get callback when its really online?
		Intent broadcastOnlineIntent = new Intent();
		broadcastOnlineIntent.setAction(de.tubs.ibr.dtn.Intent.STATE);
		broadcastOnlineIntent.putExtra("state", DaemonState.ONLINE.name());
		broadcastOnlineIntent.addCategory(Intent.CATEGORY_DEFAULT);
		mService.sendBroadcast(broadcastOnlineIntent);

		// blocking main loop
		NativeDaemonWrapper.daemonMainLoop();

		// broadcast offline state
		Intent broadcastOfflineIntent = new Intent();
		broadcastOfflineIntent.setAction(de.tubs.ibr.dtn.Intent.STATE);
		broadcastOfflineIntent.putExtra("state", DaemonState.OFFLINE.name());
		broadcastOfflineIntent.addCategory(Intent.CATEGORY_DEFAULT);
		mService.sendBroadcast(broadcastOfflineIntent);
	}

	/**
	 * Create Hex String from byte array
	 * 
	 * @param data
	 * @return
	 */
	private static String toHex(byte[] data)
	{
		StringBuffer hexString = new StringBuffer();
		for (int i = 0; i < data.length; i++)
			hexString.append(Integer.toHexString(0xFF & data[i]));
		return hexString.toString();
	}

	/**
	 * Build unique endpoint id from Secure.ANDROID_ID
	 * 
	 * @param context
	 * @return
	 */
	public static SingletonEndpoint getUniqueEndpointID(Context context)
	{
		final String androidId = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("MD5");
			byte[] digest = md.digest(androidId.getBytes());
			return new SingletonEndpoint("dtn://android-" + toHex(digest).substring(4, 12) + ".dtn");
		} catch (NoSuchAlgorithmException e) {
			Log.e(TAG, "md5 not available");
		}
		return new SingletonEndpoint("dtn://android-" + androidId.substring(4, 12) + ".dtn");
	}

	/**
	 * Creates config for dtnd in specified path
	 * 
	 * @param context
	 */
	private void createConfig(Context context, String configPath)
	{
		// load preferences
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		File config = new File(configPath);

		// remove old config file
		if (config.exists()) {
			config.delete();
		}

		try {
			FileOutputStream writer = context.openFileOutput("config", Context.MODE_PRIVATE);

			// initialize default values if configured set already
			de.tubs.ibr.dtn.daemon.Preferences.initializeDefaultPreferences(context);

			// set EID
			PrintStream p = new PrintStream(writer);
			p.println("local_uri = " + preferences.getString("endpoint_id", getUniqueEndpointID(context).toString()));
			p.println("routing = " + preferences.getString("routing", "default"));

			if (preferences.getBoolean("constrains_lifetime", false)) {
				p.println("limit_lifetime = 1209600");
			}

			if (preferences.getBoolean("constrains_timestamp", false)) {
				p.println("limit_predated_timestamp = 1209600");
			}

			// limit block size to 50 MB
			p.println("limit_blocksize = 50M");

			String secmode = preferences.getString("security_mode", "disabled");

			if (!secmode.equals("disabled")) {
				File sec_folder = new File(context.getFilesDir().getPath() + "/bpsec");
				if (!sec_folder.exists() || sec_folder.isDirectory()) {
					p.println("security_path = " + sec_folder.getPath());
				}
			}

			if (secmode.equals("bab")) {
				// write default BAB key to file
				String bab_key = preferences.getString("security_bab_key", "");
				File bab_file = new File(context.getFilesDir().getPath() + "/default-bab-key.mac");

				// remove old key file
				if (bab_file.exists()) bab_file.delete();

				FileOutputStream bab_output = context.openFileOutput("default-bab-key.mac", Context.MODE_PRIVATE);
				PrintStream bab_writer = new PrintStream(bab_output);
				bab_writer.print(bab_key);
				bab_writer.flush();
				bab_writer.close();

				if (bab_key.length() > 0) {
					// enable security extension: BAB
					p.println("security_level = 1");

					// add BAB key to the configuration
					p.println("security_bab_default_key = " + bab_file.getPath());
				}
			}

			if (preferences.getBoolean("checkIdleTimeout", false)) {
				p.println("tcp_idle_timeout = 30");
			}

			// set multicast address for discovery
			p.println("discovery_address = ff02::142 224.0.0.142");

			if (preferences.getBoolean("discovery_announce", true)) {
				p.println("discovery_announce = 1");
			} else {
				p.println("discovery_announce = 0");
			}

			String internet_ifaces = "";
			String ifaces = "";

			Map<String, ?> prefs = preferences.getAll();
			for (Map.Entry<String, ?> entry : prefs.entrySet()) {
				String key = entry.getKey();
				if (key.startsWith("interface_")) {
					if (entry.getValue() instanceof Boolean) {
						if ((Boolean) entry.getValue()) {
							String iface = key.substring(10, key.length());
							ifaces = ifaces + " " + iface;

							p.println("net_" + iface + "_type = tcp");
							p.println("net_" + iface + "_interface = " + iface);
							p.println("net_" + iface + "_port = 4556");
							internet_ifaces += iface + " ";
						}
					}
				}
			}

			p.println("net_interfaces = " + ifaces);
			p.println("net_internet = " + internet_ifaces);

			// storage path
			File blobPath = DaemonStorageUtils.getStoragePath("blob");
			if (blobPath != null) {
				p.println("blob_path = " + blobPath.getPath());

				// flush storage path
				File[] files = blobPath.listFiles();
				if (files != null) {
					for (File f : files) {
						f.delete();
					}
				}
			}

			File bundlePath = DaemonStorageUtils.getStoragePath("bundles");
			if (bundlePath != null) {
				p.println("storage_path = " + bundlePath.getPath());
			}
						        
			File logPath = DaemonStorageUtils.getStoragePath("logs");
            if (logPath != null) {
                Calendar cal = Calendar.getInstance();
                String time = "" + cal.get(Calendar.YEAR) + cal.get(Calendar.MONTH) + cal.get(Calendar.DAY_OF_MONTH) + cal.get(Calendar.DAY_OF_MONTH)
                        + cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND);
                p.println("logfile = " + logPath.getPath() + "ibrdtn_" + time +".log");
            }

			/*
			 * if (preferences.getBoolean("connect_static", false)) { // add
			 * static connection p.println("static1_uri = " +
			 * preferences.getString("host_name", "dtn:none"));
			 * p.println("static1_address = " +
			 * preferences.getString("host_address", "0.0.0.0"));
			 * p.println("static1_proto = tcp"); p.println("static1_port = " +
			 * preferences.getString("host_port", "4556"));
			 * 
			 * // p.println("net_autoconnect = 120"); }
			 */

			// enable interface rebind
			p.println("net_rebind = yes");

			// flush the write buffer
			p.flush();

			// close the filehandle
			writer.close();
		} catch (IOException e) {
			Log.e(TAG, "Problem writing config", e);
		}
	}
}
