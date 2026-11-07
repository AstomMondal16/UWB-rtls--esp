# First setup: three anchors and one tag

An **anchor** stays fixed in the room. A **tag** moves with the person or object
you want to track. The app uses the anchors' known positions to locate the tag.
The manufacturer flashes the C firmware and identifies which boards are anchors
and tags before delivery. Each board creates its secure identity automatically.
Customers do not handle certificates, fingerprints, MAC addresses or IP settings.

1. Power on the three anchors and the tag. For the first test, keep them at
   roughly the same height with a clear view between them.
2. Put the anchors at three separated positions, such as three corners of the
   room. Avoid putting all three in one straight line. Leave them fixed afterward.
3. Connect your phone to a 2.4 GHz Wi-Fi network that the boards can join. A phone
   hotspot can also be used. The network must allow connected devices to talk
   to each other; guest/client isolation can prevent discovery and communication.
4. Open the app. Allow Bluetooth/Nearby Devices and local-network access when
   requested. Internet is not needed for local monitoring or phone clock sync.
5. In **Devices**, open Wi-Fi setup. A new board without saved Wi-Fi enters
   setup automatically; otherwise press its Wi-Fi/setup button (GPIO17 in the
   current reference firmware). Scan, select
   its Bluetooth name, and enter your Wi-Fi name/password. Repeat for the other
   boards. Use the board's name/label to identify which one you are setting up.
6. Tap **Scan network** and add the discovered boards. Check that three are
   anchors and one is a tag. The first connection saves each board's identity automatically. Do this initial
   setup only on your trusted private Wi-Fi; there is no QR/PIN verification.
   Later connections must match the saved identity.
7. Name the anchors A1, A2 and A3. Measure their positions. For a 4 m by 3 m room,
   put A1 at the reference corner `(X=0, Y=0)`, A2 four metres to its right
   `(4, 0)`, and A3 three metres forward `(0, 3)`. Enter these values in each
   anchor's position settings. The same-height test plane can use `Z=0`.
   These are examples: use your actual measured distances, not the room's
   approximate dimensions.
8. Keep the app connected while the boards synchronize their clocks. The app
   sends phone time automatically when needed; sensors start afterward. Open
   **Dashboard** and move the tag. Its marker and trail should move on the
   map. Select the tag to see its motion, CIR and battery readings. If positions
   look wrong, check anchor coordinates, roles and clear ranging paths first.

After initial setup, normally just power on the boards and open the app on the
same network. Scan again if needed after addresses change. When an anchor is
moved, update its saved coordinates. Add further anchors or tags using the same
steps; this example does not fix the system to three anchors and one tag.

The current app uses coordinate fields for anchor placement. A guided room
wizard, QR setup and automatic anchor surveying are not implemented by the
HTTPS change. Final physical button/label names must match the shipped enclosure.

If the app reports an unexpected identity change, do not simply dismiss it.
After an expected board reset only, use Devices > Edit > Pair again, confirm,
and save on trusted private Wi-Fi. Initial pairing can be impersonated by an
attacker present then; later connections are checked against the saved key.
