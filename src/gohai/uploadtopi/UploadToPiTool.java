/**
 * you can put a one sentence description of your tool here.
 *
 * ##copyright##
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
 * @author   ##author##
 * @modified ##date##
 * @version  ##tool.prettyVersion##
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
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;


public class UploadToPiTool implements Tool {
  Base base;
  SSHClient ssh;
  static Thread t;

  String hostname;
  String username;
  String password;
  boolean persistent;
  boolean autostart;


  public String getMenuTitle() {
    return "Upload to Pi";
  }


  public void init(Base base) {
    this.base = base;
    loadPreferences();
    savePreferences();
  }


  // XXX: https://github.com/hierynomus/sshj/blob/master/examples/src/main/java/net/schmizz/sshj/examples/KeepAlive.java
  // XXX: implement method to retrieve Pi's serial number
  // XXX: implement method to retrieve Pi's network IPs & MAC addresses
  // XXX: implement method to maximize root partition


  public void run() {
    final Editor editor = base.getActiveEditor();
    final String sketchName = editor.getSketch().getName();
    final String sketchPath = editor.getSketch().getFolder().getAbsolutePath();

    // this assumes the working directory is home at the beginning of a ssh/sftp session
    // "~" didn't work (no such file)
    final String dest = (persistent) ? "." : "/tmp";

    // already running?
    if (t != null) {
      t.interrupt();
      try {
        t.join();
      } catch (InterruptedException e) {
        System.err.println("Error joining thread in releaseInterrupt: " + e.getMessage());
      }
      t = null;
      // XXX: disconnect?
    }

    editor.getConsole().clear();

    // this doesn't trigger the "Save as" dialog for unnamed sketches, but instead saves
    // them in the temporary location that is also used for compiling
    try {
      editor.getSketch().save();
    } catch (Exception e) {
      editor.statusError("Cannot save sketch");
      return;
    }

    try {
      exportSketch();
    } catch (Exception e) {
      editor.statusError("Cannot export sketch");
      System.err.println(e);
      return;
    }

    // XXX: there doesn't seem to be a way to handle the use pressing the stop button

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
          } else {
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
          System.err.println(e);
          disconnect();
          return;
        }

        editor.statusNotice("Running " + sketchName + " on the Raspberry Pi");
        try {
          int retVal = runRemoteSketch(dest, sketchName);
          if (retVal == Integer.MAX_VALUE) {
            // sketch is still running
          } else if (retVal == 0) {
            // clean exit
            editor.statusNotice("Sketch " + sketchName + " ended");
          } else {
            // error?
            editor.statusError("Sketch + " + sketchName + " ended with exit code " + retVal);
          }
        } catch (Exception e) {
          editor.statusError("Error running " + sketchName);
          System.err.println(e);
        }

        disconnect();

      }
    }, "Upload to Pi");

    t.start();
  }


  public void addAutostart(String dest, String sketchName) throws IOException {
    Session session = ssh.startSession();
    // XXX: sync since users might be inclined to power-cyle the Pi the hard way?
    Command cmd = session.exec("echo \"@" + dest + "/" + sketchName + "/" + sketchName + " --uploadtopi-managed\" >> .config/lxsession/LXDE-pi/autostart");
    cmd.join(3, TimeUnit.SECONDS);
    if (cmd.getExitStatus() != 0) {
      // not critical
      System.err.println("Error modifying .config/lxsession/LXDE-pi/autostart");
    }
    session.close();
  }


  public static SSHClient connect(String host, String username, String password) throws IOException, TransportException, UserAuthException {
    SSHClient ssh = new SSHClient();
    ssh.loadKnownHosts();
    // XXX: needs JZlib
    //ssh.useCompression();

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
        //ssh.useCompression();
        ssh.connect(host);
      } else {
        throw e;
      }
    }

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

    Method javaModeMethod = mode.getClass().getMethod("handleExportApplication", sketch.getClass());
    javaModeMethod.invoke(mode, sketch);
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
    Command cmd = session.exec("DISPLAY=:0 " + dest + "/" + sketchName + "/" + sketchName + " --uploadtopi-managed");

    // redirect output to stdout and stderr
    new StreamCopier(cmd.getInputStream(), System.out)
                    .bufSize(cmd.getLocalMaxPacketSize())
                    .spawn("stdout");

    new StreamCopier(cmd.getErrorStream(), System.err)
                    .bufSize(cmd.getLocalMaxPacketSize())
                    .spawn("stderr");

    // wait for thread to end
    do {
      // XXX: or signal
    } while (cmd.isOpen());

    session.close();

    return cmd.getExitStatus();
  }


  public void savePreferences() {
    Preferences.set("gohai.uploadtopi.hostname", hostname);
    Preferences.set("gohai.uploadtopi.username", username);
    Preferences.set("gohai.uploadtopi.password", password);
    Preferences.setBoolean("gohai.uploadtopi.persistent", persistent);
    Preferences.setBoolean("gohai.uploadtopi.autostart", autostart);
  }


  public void stopSketches() throws IOException {
    Session session = ssh.startSession();
    // kill any Processing sketch we started either directly or through autostart
    Command cmd = session.exec("pgrep -f \"uploadtopi-managed\" | xargs kill -9");
    cmd.join(3, TimeUnit.SECONDS);
    // cmd.getExitStatus() throws a NPE here, not sure why - ignore for now
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
