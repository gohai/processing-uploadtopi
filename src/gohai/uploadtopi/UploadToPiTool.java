/**
 * Tool to upload and run sketches on Raspberry Pi devices.
 *
 * Copyright (c) The Processing Foundation 2016
 * Developed by Gottfried Haider
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General
 * Public License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA  02111-1307  USA
 *
 * @author   Gottfried Haider
 */

package gohai.uploadtopi;

import processing.app.Base;
import processing.app.Mode;
import processing.app.Preferences;
import processing.app.Sketch;
import processing.app.tools.Tool;
import processing.app.ui.Editor;
import processing.app.ui.EditorConsole;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;


// XXX: there doesn't seem to be a way to handle the use pressing the stop button
// XXX: implement method to retrieve Pi's serial number
// XXX: implement method to retrieve Pi's network IPs & MAC addresses


public class UploadToPiTool implements Tool {
  Base base;
  SSHClient ssh;
  Thread t;

  String hostname;
  String username;
  String password;
  boolean persistent;
  boolean autostart;
  boolean logging;


  public String getMenuTitle() {
    return "Upload to Pi";
  }


  public void init(Base base) {
    this.base = base;
    loadPreferences();
    // saving the preferences adds them to the txt file for the user to edit
    savePreferences();
  }


  public void run() {
    final Editor editor = base.getActiveEditor();
    final String sketchName = editor.getSketch().getName();
    final String sketchPath = editor.getSketch().getFolder().getAbsolutePath();

    // this assumes the working directory is home at the beginning of a ssh/sftp session
    // "~" didn't work (no such file)
    final String dest = (persistent) ? "." : "/tmp";

    // already running?
    if (t != null) {
      // terminate thread
      t.interrupt();
      try {
        // wait for it to finish
        t.join();
      } catch (InterruptedException e) {
        System.err.println("Error joining thread: " + e.getMessage());
      }
      t = null;
      // the thread should already have called this, but in case it didn't
      disconnect();
    }

    editor.getConsole().clear();

    // this doesn't trigger the "Save as" dialog for unnamed sketches, but instead saves
    // them in the temporary location that is also used for compiling
    try {
      editor.getSketch().save();
    } catch (Exception e) {
      editor.statusError("Cannot save sketch");
      // DEBUG
      e.printStackTrace();
      System.err.println(e);
      return;
    }

    try {
      exportSketch();
    } catch (Exception e) {
      editor.statusError("Cannot export sketch");
      if (e instanceof InvocationTargetException) {
        System.err.println("Most likely caused by a syntax error. Press the Run button to get more information on where the problem lies.");
      } else {
        // DEBUG
        e.printStackTrace();
        System.err.println(e);
      }
      return;
    }

    t = new Thread(new Runnable() {
      public void run() {

        try {
          editor.statusNotice("Connecting to " + hostname + " ...");
          ssh = connect(hostname, username, password);
        } catch (Exception e) {
          editor.statusError("Cannot connect to " + hostname);
          if (e instanceof UnknownHostException) {
            System.err.println("Unknown host");
          } else if (e instanceof UserAuthException) {
            System.err.println("Wrong username or password");
          } else if (e instanceof ConnectException && e.getMessage().equals("Connection refused")) {
            System.err.println("No SSH server running?");
          } else if (e instanceof SocketTimeoutException) {
            System.err.println("A timeout occurred");
          } else if (e instanceof ConnectionException && e.getMessage().equals("Operation timed out")) {
            System.err.println("A timeout occurred");
          } else {
            // DEBUG
            e.printStackTrace();
            System.err.println(e);
          }
          return;
        }

        try {
          editor.statusNotice("Uploading " + sketchName + " ...");
          stopSketches();
          removeSketch(dest, sketchName);
          uploadSketch(sketchPath + File.separator + "application.linux-armv6hf", dest, sketchName);
          removeAutostarts();
          if (autostart) {
            addAutostart(dest, sketchName);
          }
        } catch (Exception e) {
          editor.statusError("Cannot upload " + sketchName);
          // DEBUG
          e.printStackTrace();
          System.err.println(e);
          disconnect();
          return;
        }

        try {
          editor.statusNotice("Syncing disks ...");
          syncDisks();
        } catch (Exception e) {
          editor.statusError("Cannot sync disks");
          // DEBUG
          e.printStackTrace();
          System.err.println(e);
          disconnect();
          return;
        }

        editor.statusNotice("Running " + sketchName + " on the Raspberry Pi");
        try {
          int retVal = runRemoteSketch(dest, sketchName);
          if (retVal == 0) {
            // clean exit
            editor.statusNotice("Sketch " + sketchName + " ended");
          } else {
            // error?
            editor.statusError("Sketch " + sketchName + " ended with exit code " + retVal);
          }
        } catch (Exception e) {
          editor.statusError("Error running " + sketchName);
          // DEBUG
          e.printStackTrace();
          System.err.println(e);
        }

        disconnect();

      }
    }, "Upload to Pi");

    t.start();
  }


  public void addAutostart(String dest, String sketchName) throws IOException {
    Session session = ssh.startSession();
    String cmdString = dest + "/" + sketchName + "/" + sketchName + " --uploadtopi-managed";

    Command cmd;
    if (logging) {
      // LXDE autostart doesn't support spaces in its arguments, so we have to add an aux shell script
      cmd = session.exec("echo '" + cmdString + " >>" + dest + "/" + sketchName + "/" + sketchName + ".log 2>&1' > .config/lxsession/LXDE-pi/processing.sh && chmod a+x .config/lxsession/LXDE-pi/processing.sh");
      cmd.join(3, TimeUnit.SECONDS);
      session.close();
      session = ssh.startSession();
      cmd = session.exec("echo '.config/lxsession/LXDE-pi/processing.sh --uploadtopi-managed' >> .config/lxsession/LXDE-pi/autostart");
      cmd.join(3, TimeUnit.SECONDS);
    } else {
      cmd = session.exec("echo '" + cmdString + " >> .config/lxsession/LXDE-pi/autostart");
    }

    if (cmd.getExitStatus() != 0) {
      // not critical
      System.err.println("Error modifying .config/lxsession/LXDE-pi/autostart");
    }
    session.close();
  }


  public static SSHClient connect(String host, String username, String password) throws IOException, TransportException, UserAuthException {
    DefaultConfig defaultConfig = new DefaultConfig();
    defaultConfig.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
    SSHClient ssh = new SSHClient(defaultConfig);
    // seems to throw an IOException on Windows
    try {
      ssh.loadKnownHosts();
    } catch (Exception e) {}

    // set a timeout to try to work around this bizzare timeout error on some OS X machines:
    // java.net.ConnectException: Operation timed out
    //      at java.net.PlainSocketImpl.socketConnect(Native Method)
    //      at java.net.AbstractPlainSocketImpl.doConnect(AbstractPlainSocketImpl.java:350)
    ssh.setConnectTimeout(5000);

    // we could enable compression here with
    //ssh.useCompression();
    // but the Pi is likely in the local network anyway (would need JZlib)

    try {
      ssh.connect(host);
    } catch (TransportException e) {
      if (e.getDisconnectReason() == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) {
        String msg = e.getMessage();
        String[] split = msg.split("`");
        String fingerprint = split[3];
        // try again
        ssh = new SSHClient();
        // this doesn't update the known_hosts file
        ssh.addHostKeyVerifier(fingerprint);
        ssh.setConnectTimeout(5000);
        //ssh.useCompression();
        ssh.connect(host);
      } else {
        throw e;
      }
    }

    // send keep-alife nop every minute
    ssh.getConnection().getKeepAlive().setKeepAliveInterval(60);
    ssh.authPassword(username, password);
    return ssh;
  }


  public void disconnect() {
    if (ssh != null) {
      try {
        ssh.disconnect();
      } catch (Exception e) {
      }
      ssh = null;
    }
  }


  public void exportSketch() throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    Editor editor = base.getActiveEditor();
    Mode mode = editor.getMode();
    Sketch sketch = editor.getSketch();

    String oldSetting = Preferences.get("export.application.platform_linux");
    Preferences.set("export.application.platform_linux", "true");

    try {
      Method javaModeMethod = mode.getClass().getMethod("handleExportApplication", sketch.getClass());
      javaModeMethod.invoke(mode, sketch);
    } finally {
      Preferences.set("export.application.platform_linux", oldSetting);
    }
  }


  private void loadPreferences() {
    hostname = Preferences.get("gohai.uploadtopi.hostname");
    if (hostname == null) {
      hostname = "raspberrypi.local";
    }
    username = Preferences.get("gohai.uploadtopi.username");
    if (username == null) {
      username = "pi";
    }
    password = Preferences.get("gohai.uploadtopi.password");
    if (password == null) {
      password = "raspberry";
    }
    String tmp = Preferences.get("gohai.uploadtopi.persistent");
    if (tmp == null) {
      persistent = true;
    } else {
      persistent = Boolean.parseBoolean(tmp);
    }
    tmp = Preferences.get("gohai.uploadtopi.autostart");
    if (tmp == null) {
      autostart = true;
    } else {
      autostart = Boolean.parseBoolean(tmp);
    }
    tmp = Preferences.get("gohai.uploadtopi.logging");
    if (tmp == null) {
      logging = true;
    } else {
      logging = Boolean.parseBoolean(tmp);
    }
  }


  public void removeAutostarts() throws IOException {
    Session session = ssh.startSession();
    Command cmd = session.exec("sed -i \"/uploadtopi-managed/d\" .config/lxsession/LXDE-pi/autostart");
    cmd.join(3, TimeUnit.SECONDS);
    if (cmd.getExitStatus() != 0) {
      // not critical
      System.err.println("Error modifying .config/lxsession/LXDE-pi/autostart");
    }
    session.close();
  }


  public void removeSketch(String dest, String sketchName) throws IOException {
    // try to remove the current sketch's directory
    // necessary as sftp put w/ rename doesn't work if the target (directory) exists
    Session session = ssh.startSession();
    Command cmd = session.exec("rm -Rf " + dest + "/" + sketchName);
    cmd.join(10, TimeUnit.SECONDS);
    if (cmd.getExitStatus() != 0) {
      throw new RuntimeException("Error removing directory " + dest + "/"  + sketchName);
    }
    session.close();
  }


  public int runRemoteSketch(String dest, String sketchName) throws IOException {
    Session session = ssh.startSession();
    // --uploadtopi-managed is a dummy argument we use in removeSketch() to indentify ours
    String cmdString = "DISPLAY=:0 " + dest + "/" + sketchName + "/" + sketchName + " --uploadtopi-managed";
    Command cmd = session.exec(cmdString);

    // redirect output to stdout and stderr
    new StreamCopier(cmd.getInputStream(), System.out)
                    .bufSize(cmd.getLocalMaxPacketSize())
                    .spawn("stdout");

    new StreamCopier(cmd.getErrorStream(), System.err)
                    .bufSize(cmd.getLocalMaxPacketSize())
                    .spawn("stderr");

    do {
      // wait for sketch execution to end
      Thread.yield();
    } while (cmd.isOpen() && !t.isInterrupted());

    try {
      // when the current thread is interrupted the following line throws a
      // ConnectionException, likely due to the InterruptedException pending
      cmd.close();
      session.close();
      return cmd.getExitStatus();
    } catch (Exception e) {
      return 0;
    }
  }


  public void savePreferences() {
    Preferences.set("gohai.uploadtopi.hostname", hostname);
    Preferences.set("gohai.uploadtopi.username", username);
    Preferences.set("gohai.uploadtopi.password", password);
    Preferences.setBoolean("gohai.uploadtopi.persistent", persistent);
    Preferences.setBoolean("gohai.uploadtopi.autostart", autostart);
    Preferences.setBoolean("gohai.uploadtopi.logging", logging);
  }


  public void stopSketches() throws IOException {
    Session session = ssh.startSession();
    // kill any Processing sketch we started either directly or through autostart
    Command cmd = session.exec("pgrep -f \"uploadtopi-managed\" | xargs kill -9");
    cmd.join(3, TimeUnit.SECONDS);
    // cmd.getExitStatus() throws a NPE here, not sure why - ignore for now
    session.close();
  }


  public void syncDisks() throws IOException {
    Session session = ssh.startSession();
    Command cmd = session.exec("sync");
    cmd.join(30, TimeUnit.SECONDS);
    if (cmd.getExitStatus() != 0) {
      // not critical
      System.err.println("Error syncing disks. Make sure you power off the Pi safely to prevent file corruption.");
    }
    session.close();
  }


  public void uploadSketch(String localDir, String dest, String sketchName) throws IOException {
    SFTPClient sftp = ssh.newSFTPClient();
    // XXX: only upload changed files?
    sftp.put(localDir, dest + "/" + sketchName);
    sftp.chmod(dest + "/" + sketchName + "/" + sketchName, 0755);
    sftp.close();
  }
}
