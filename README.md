# Upload to Pi Tool

### Description

This tool adds an _Upload to Pi_ menu item under _Tools_. Invoking it will compile the current sketch, upload it to a connected Raspberry Pi, and execute it there. Any output of your sketch, such as from `println`, is displayed on your local console.

By default, a connection with _raspberrypi.local_ is attempted, using the default username (_raspberry_) and password (_pi_). These settings can be changed by modifying the values in Processing's `preferences.txt` file. See section _Configuration_ for more details.

Sketches are uploaded to the default user's home directory (`/home/pi`). The most recently uploaded sketch is also automatically started whenever the Raspberry Pi boots up. This behavior can be changed, if needed.

Since this tool needs to make some assumption about the Pi's system configuration, only the Raspbian operating system is currently supported. (We tested it against its _March 2016_ release.)

### Install with the Contribution Manager

Add contributed tools by selecting the menu item _Tools_ → _Add Tool..._ This will open the Contribution Manager, where you can browse for Upload to Pi, or any other tool you want to install.

Not all available tools have been converted to show up in this menu. If a tool isn't there, it will need to be installed manually by following the instructions below.

### Manual Install

Contributed tools may be downloaded separately and manually placed within the `tools` folder of your Processing sketchbook. To find (and change) the Processing sketchbook location on your computer, open the Preferences window from the Processing application (PDE) and look for the "Sketchbook location" item at the top.

By default the following locations are used for your sketchbook folder: 
  * For Mac users, the sketchbook folder is located inside `~/Documents/Processing` 
  * For Windows users, the sketchbook folder is located inside `My Documents/Processing`

Download Upload to Pi from https://github.com/gohai/processing-uploadtopi

Unzip and copy the contributed tool's folder into the `tools` folder in the Processing sketchbook. You will need to create this `tools` folder if it does not exist.
    
The folder structure for tool Upload to Pi should be as follows:

```
Processing
  tools
    Upload to Pi
      examples
      tool
        Upload to Pi.jar
      reference
      src
```
                      
Some folders like `examples` or `src` might be missing. After tool Upload to Pi has been successfully installed, restart the Processing application.

### Configuration

The following settings can be modified by editing Processing's `preferences.txt` file:

`gohai.uploadtopi.hostname` - the IP address or hostname of your Raspberry Pi; This defaults to `raspberrypi.local`, which is the default mDNS address of the Raspberry Pi on the local network. If you're using Windows, which currently doesn't support mDNS resolution out of the box, or you're having more than one Raspberry Pi connected to your network, you might need to change this value. See [here](https://learn.adafruit.com/bonjour-zeroconf-networking-for-windows-and-linux/overview) for more information how to enable mDNS resolution on different operating systems.

`gohai.uploadtopi.username` - the username to use with the Pi, defaults to `raspberry`

`gohai.uploadtopi.password` - the password to use, defaults to `pi`

`gohai.uploadtopi.persistent` - whether or not to upload the sketch to persistent memory, defaults to `true`; If set to `false`, the sketch will be uploaded to `/tmp` instead of `/home/pi`.

`gohai.uploadtopi.autostart` - whether or not to automatically start the sketch after bootup, defaults to `true`

`gohai.uploadtopi.logging` - whether to write the output of the sketch (including any error messages) to a .log file in the sketch folder on the Raspberry Pi when automatically started after bootup, defaults to `true`

### Troubleshooting

If you're having trouble, please file issues [here](https://github.com/gohai/processing-uploadtopi/issues/new).
