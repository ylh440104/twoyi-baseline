/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ControlSocketTest {
    @Test
    public void listenerAcceptsAndReleasesConsecutiveLocalClients() throws Exception {
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Control listener did not become ready",
                TwoyiSocketServer.getInstance(host).awaitListening(5000));
        LocalSocketAddress address = new LocalSocketAddress(
                GuestEndpoints.DEFAULT_CONTROL_SOCKET, LocalSocketAddress.Namespace.ABSTRACT);
        // More than eight connections makes a leaked client worker observable.
        for (int attempt = 0; attempt < 12; attempt++) {
            sendAndAwaitClose(address, TwoyiMessenger.PING);
        }

        TwoyiStatusManager status = TwoyiStatusManager.getInstance();
        assertFalse("Guest must be stopped during the control test", status.isStarted());
        try {
            sendAndAwaitClose(address, "BOOT_COMPLETED_EXTRA");
            assertFalse("An unknown packet changed the boot state", status.isStarted());
            sendAndAwaitClose(address, "BOOT_COMPLETED\r\n\0");
            assertTrue("Legacy terminated boot notification was ignored", status.isStarted());
        } finally {
            status.reset();
        }
    }

    private static void sendAndAwaitClose(LocalSocketAddress address, String packet) throws Exception {
        try (LocalSocket client = new LocalSocket(LocalSocket.SOCKET_SEQPACKET)) {
            client.connect(address);
            assertEquals(Process.myUid(), client.getPeerCredentials().getUid());
            client.setSoTimeout(3000);
            client.getOutputStream().write(packet.getBytes(StandardCharsets.US_ASCII));
            client.shutdownOutput();
            assertEquals("Server did not close the connection after EOF", -1,
                    client.getInputStream().read());
        }
    }
}
