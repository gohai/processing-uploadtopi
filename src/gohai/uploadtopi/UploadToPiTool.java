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
import processing.app.Sketch;
import processing.app.tools.Tool;
import processing.app.ui.Editor;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.SSHClient;


public class UploadToPiTool implements Tool {
  Base base;
  SSHClient ssh;


  public String getMenuTitle() {
    return "Upload to Pi";
  }


  public void init(Base base) {
    this.base = base;
  }


  public void run() {
    Editor editor = base.getActiveEditor();
    String sketchPath = editor.getSketch().getFolder().getAbsolutePath();

    // XXX: put this in an extra thread?
    // XXX: needs save
    exportSketch();

    System.out.println("Connecting...");
    // XXX: config
    ssh = connect("raspberrypi.local", "pi", "raspberry");
    if (ssh == null) {
      return;
    }

    System.out.print("Uploading... ");
    removeSketch("/tmp", editor.getSketch().getName());
    uploadSketch(sketchPath + File.separator + "application.linux-armv6hf", "/tmp", editor.getSketch().getName());
    System.out.println("Done.");

    int retVal = runRemoteSketch("/tmp", editor.getSketch().getName());
    System.out.println("Sketch ended.");
  }


  public static SSHClient connect(String host, String username, String password) {
    SSHClient ssh = new SSHClient();
    try {
      ssh.loadKnownHosts();
      // XXX: handle HOST_KEY_NOT_VERIFYABLE
      ssh.connect(host);
      ssh.authPassword(username, password);
      return ssh;
    } catch (IOException e) {
      System.out.println(e);
      return null;
    }
  }


  public boolean exportSketch() {
    Editor editor = base.getActiveEditor();

    try {
      Mode mode = editor.getMode();
      Sketch sketch = editor.getSketch();

      Method javaModeMethod = mode.getClass().getMethod("handleExportApplication", sketch.getClass());
      javaModeMethod.invoke(mode, sketch);
      return true;
    } catch (Exception e) {
      System.out.println(e);
      return false;
    }
  }


  public void removeSketch(String dest, String sketchName) {
    try {
      Session session = ssh.startSession();
      // only one exec() per session
      Command cmd = session.exec("pkill -P $(pgrep " + sketchName + ") && rm -Rf " + dest + "/" + sketchName);
      cmd.join(3, TimeUnit.SECONDS);
      if (cmd.getExitStatus() != 0) {
        // XXX: unexpected return value
      }
      session.close();
    } catch (IOException e) {
      System.out.println(e);
    }
  }


  public int runRemoteSketch(String dest, String sketchName) {
    try {
      Session session = ssh.startSession();
      Command cmd = session.exec("DISPLAY=:0 " + dest + "/" + sketchName + "/" + sketchName);
      // XXX: output while sketch is running
      // XXX: handle stop button
      cmd.join(10, TimeUnit.SECONDS);
      System.out.println(IOUtils.readFully(cmd.getInputStream()).toString());
      return cmd.getExitStatus();
    } catch (IOException e) {
      System.out.println(e);
      return -1;
    }
  }


  public void uploadSketch(String localDir, String dest, String sketchName) {
    try {
      SFTPClient sftp = ssh.newSFTPClient();
      // XXX: only upload changed files?
      sftp.put(localDir, dest + "/" + sketchName);
      sftp.chmod(dest + "/" + sketchName + "/" + sketchName, 0755);
      sftp.close();
    } catch (IOException e) {
      System.out.println(e);
    }
  }
}
