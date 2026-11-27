# Security

This fork records calls and runs a recorder as the shell user through the
phone's own Wireless debugging. A hole in it can expose someone's calls, so
reports are welcome, privately.

## How to report

Use the repository's **Security** tab and choose **Report a vulnerability**.
Do not open a public issue or discussion before a fix ships. If private
reporting is not enabled, ask the repository owner for a private address
without describing the vulnerability in public.

Say what an attacker can reach and how, the phone, its Android version, the
JemRec version, and the steps that show it. Do not attach recordings of real
calls or anyone's name or number.

This is a small fork, so there is no fixed reply time and no bounty.

## Which versions

Only the latest build from this repository. Fixes are not carried back to
older builds.

## What counts

Anything that lets another app, a website, or another device get at what
JemRec guards: the recordings and what is noted about them, starting or
stopping a recording, the recorder's authenticated socket, the Termux AI
link, or the debugging settings JemRec controls.

A way into how the app is built or released, such as the workflows, signing
keys or published APKs, also counts, because it could put someone else's code
into a signed build.

What is already documented is not a hole: debugging stays on while JemRec is
on, and while it is off unless you choose otherwise, so a computer that was
paired with the phone can open a shell on it. Start without Wi-Fi opens port
5555, where a debug request still needs a yes on the phone. A way around that
pairing or that yes is a hole, though.
