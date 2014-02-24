# Cloud Keyboard

Cloud Keyboard is an app that lets you type on your Android using your
computer keyboard. While there are many apps that allow you do to that
over Wifi, I have
yet found an app that lets you do so using a separate server.

However, a remote keyboard app with a server has its advantages. For
example:

1. Your PC does not have to be in the same Wifi network as your Android
device. This occurs, for example, when you are using lab computers at
the university and the university's Wifi connection.
2. Your PC is behind a VPN
3. Your workplace does not have Wifi. Your phone is connected to the
Internet via a 3G connection.

The disadvantage, of course, is that you will see latencies increase
significantly. The assumption is that typing on a touchscreen will kill
you more than latency does, which is why you are interested in this
project.

You will also need a publicly accessible server somewhere.

## Getting the App

TBD

## Getting the server

See [cloud-keyboard-server](https://github.com/xkjyeah/cloud-keyboard-app).

The server protocol should be simple enough if you want to roll your own
server, e.g. to write a PHP port.

The reference server above runs on node.js, so it does not work under the
regular CGI/mod_php/mod_perl framework. So, your regular shared hosting
provider may not be able to support it. 
The upside is, as of the time of writing, it fits into ONE
source file and operates entirely off memory.

If you run the above server, e.g. on `example.com` with port 8080, then
set cloud-keyboard-app's server address to `http://example.com:8080/`.

