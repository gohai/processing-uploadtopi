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
import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.IOUtils;
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


  // XXX: https://github.com/hierynomus/sshj/blob/master/examples/src/main/java/net/schmizz/sshj/examples/RudimentaryPTY.java
  // XXX: https://github.com/hierynomus/sshj/blob/master/examples/src/main/java/net/schmizz/sshj/examples/KeepAlive.java
  // XXX: implement method to retrieve Pi's serial number
  // XXX: implement method to retrieve Pi's network IPs & MAC addresses
  // XXX: implement method to maximize root partition


  public void run() {
    Editor editor = base.getActiveEditor();
    String sketchName = editor.getSketch().getName();
    String sketchPath = editor.getSketch().getFolder().getAbsolutePath();

    // this assumes the working directory is home at the beginning of a ssh/sftp session
    // "~" didn't work (no such file)
    String dest = (persistent) ? "." : "/tmp";

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

    // XXX: put this in an extra thread
    // XXX: output while sketch is running
    // XXX: handle stop button

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
        System.err.println("Unable to connect. No SSH server running?");
      } else {
        System.err.println(e);
      }
      return;
    }

    try {
      editor.statusNotice("Uploading " + sketchName + " ...");
      removeSketch(dest, sketchName);
      uploadSketch(sketchPath + File.separator + "application.linux-armv6hf", dest, sketchName);
    } catch (Exception e) {
      editor.statusError("Cannot upload " + sketchName);
      System.err.println(e);
      disconnect();
      return;
    }

    editor.statusNotice("Running " + sketchName + " on your Raspberry Pi");
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


  public static SSHClient connect(String host, String username, String password) throws IOException, TransportException, UserAuthException {
    SSHClient ssh = new SSHClient();
    ssh.loadKnownHosts();
    // XXX: needs
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


  public void removeSketch(String dest, String sketchName) throws IOException {
    Session session = ssh.startSession();
    // session only handles a single exec call
    // XXX: explain command (pkill currently fails if no processs is running)
    Command cmd = session.exec("pkill -P $(pgrep " + sketchName + ") && rm -Rf " + dest + "/" + sketchName);
    cmd.join(3, TimeUnit.SECONDS);
    if (cmd.getExitStatus() != 0) {
      throw new RuntimeException("removeSketch returned unexpected exit code " + cmd.getExitStatus());
    }
    session.close();
  }


  public int runRemoteSketch(String dest, String sketchName) throws IOException {
    Session session = ssh.startSession();
    Command cmd = session.exec("DISPLAY=:0 " + dest + "/" + sketchName + "/" + sketchName);
    try {
      cmd.join(10, TimeUnit.SECONDS);
    } catch (ConnectionException e) {
      if (e.getMessage().equals("Timeout expired")) {
        // sketch is still running, which is expected for now
        return Integer.MAX_VALUE;
      } else {
        throw e;
      }
    }
    // print output to console
    // XXX: receive stderr
    System.out.println(IOUtils.readFully(cmd.getInputStream()).toString());
    // XXX: try closing session
    return cmd.getExitStatus();
  }


  public void savePreferences() {
    Preferences.set("gohai.uploadtopi.hostname", hostname);
    Preferences.set("gohai.uploadtopi.username", username);
    Preferences.set("gohai.uploadtopi.password", password);
    Preferences.setBoolean("gohai.uploadtopi.persistent", persistent);
    Preferences.setBoolean("gohai.uploadtopi.autostart", autostart);
  }


  public void uploadSketch(String localDir, String dest, String sketchName) throws IOException {
    SFTPClient sftp = ssh.newSFTPClient();
    // XXX: only upload changed files?
    sftp.put(localDir, dest + "/" + sketchName);
    sftp.chmod(dest + "/" + sketchName + "/" + sketchName, 0755);
    sftp.close();
  }
}
