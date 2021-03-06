/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
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

package ca.psiphon.ploggy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.Pair;

/**
 * Component tests.
 *
 * Covered (by end-to-end request from one peer to another through Tor):
 * - TorWrapper
 * - Identity
 * - X509
 * - HiddenService
 * - WebClient
 * - WebServer
 */
public class Tests {

    private static final String LOG_TAG = "Tests";

    private static Timer mTimer = new Timer();

    public static void scheduleComponentTests() {
        mTimer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        Tests.runComponentTests();
                    }
                },
                2000);
    }

    private static class MockRequestHandler implements WebServer.RequestHandler {

        private final ExecutorService mThreadPool = Executors.newCachedThreadPool();
        private final Date mMockTimestamp;
        private final double mMockLatitude;
        private final double mMockLongitude;
        private final String mMockAddress;

        MockRequestHandler() {
            mMockTimestamp = new Date();
            mMockLatitude = Math.random()*100.0 - 50.0;
            mMockLongitude = Math.random()*100.0 - 50.0;
            mMockAddress = "301 Front St W, Toronto, ON M5V 2T6";
        }

        public void stop() {
            Utils.shutdownExecutorService(mThreadPool);
        }

        @Override
        public void submitWebRequestTask(Runnable task) {
            mThreadPool.execute(task);
        }

        public Data.Status getMockStatus() {
            return new Data.Status(
                    Arrays.asList(new Data.Message(mMockTimestamp, "", null)),
                    new Data.Location(
                        mMockTimestamp,
                        mMockLatitude,
                        mMockLongitude,
                        10,
                        mMockAddress));
        }

        @Override
        public Data.Status handlePullStatusRequest(String friendId) throws Utils.ApplicationError {
            Log.addEntry(LOG_TAG, "handle pull status request...");
            return getMockStatus();
        }

        @Override
        public void handlePushStatusRequest(String friendId, Data.Status status) throws Utils.ApplicationError {
            Log.addEntry(LOG_TAG, "handle push status request...");
        }

        @Override
        public DownloadResponse handleDownloadRequest(
                String friendCertificate, String resourceId, Pair<Long, Long> range) throws Utils.ApplicationError {
            Log.addEntry(LOG_TAG, "handle download request...");
            return null;
        }
    }

    public static void runComponentTests() {
        WebServer selfWebServer = null;
        MockRequestHandler selfRequestHandler = null;
        WebServer friendWebServer = null;
        MockRequestHandler friendRequestHandler = null;
        TorWrapper selfTor = null;
        TorWrapper friendTor = null;
        try {

            Log.addEntry(LOG_TAG, "Make self...");
            String selfNickname = "Me";
            HiddenService.KeyMaterial selfHiddenServiceKeyMaterial = HiddenService.generateKeyMaterial();
            X509.KeyMaterial selfX509KeyMaterial = X509.generateKeyMaterial(selfHiddenServiceKeyMaterial.mHostname);
            Data.Self self = new Data.Self(
                    Identity.makeSignedPublicIdentity(
                            selfNickname,
                            selfX509KeyMaterial,
                            selfHiddenServiceKeyMaterial),
                    Identity.makePrivateIdentity(
                            selfX509KeyMaterial,
                            selfHiddenServiceKeyMaterial),
                    new Date());

            Log.addEntry(LOG_TAG, "Make friend...");
            String friendNickname = "My Friend";
            HiddenService.KeyMaterial friendHiddenServiceKeyMaterial = HiddenService.generateKeyMaterial();
            X509.KeyMaterial friendX509KeyMaterial = X509.generateKeyMaterial(friendHiddenServiceKeyMaterial.mHostname);
            Data.Self friendSelf = new Data.Self(
                    Identity.makeSignedPublicIdentity(
                            friendNickname,
                            friendX509KeyMaterial,
                            friendHiddenServiceKeyMaterial),
                    Identity.makePrivateIdentity(
                            friendX509KeyMaterial,
                            friendHiddenServiceKeyMaterial),
                    new Date());
            Data.Friend friend = new Data.Friend(friendSelf.mPublicIdentity, new Date());

            // Not running hidden service for other friend: this is to test multiple client certs in the web server
            Log.addEntry(LOG_TAG, "Make other friend...");
            HiddenService.KeyMaterial otherFriendHiddenServiceKeyMaterial = HiddenService.generateKeyMaterial();
            X509.KeyMaterial otherFriendX509KeyMaterial = X509.generateKeyMaterial(otherFriendHiddenServiceKeyMaterial.mHostname);

            Log.addEntry(LOG_TAG, "Make unfriendly key material...");
            HiddenService.KeyMaterial unfriendlyHiddenServiceKeyMaterial = HiddenService.generateKeyMaterial();
            X509.KeyMaterial unfriendlyX509KeyMaterial = X509.generateKeyMaterial(unfriendlyHiddenServiceKeyMaterial.mHostname);

            Log.addEntry(LOG_TAG, "Start self web server...");
            List<String> selfPeerCertificates = new ArrayList<String>();
            selfPeerCertificates.add(friend.mPublicIdentity.mX509Certificate);
            selfPeerCertificates.add(otherFriendX509KeyMaterial.mCertificate);
            selfRequestHandler = new MockRequestHandler();
            selfWebServer = new WebServer(selfRequestHandler, selfX509KeyMaterial, selfPeerCertificates);
            try {
                selfWebServer.start();
            } catch (IOException e) {
                throw new Utils.ApplicationError(LOG_TAG, e);
            }

            Log.addEntry(LOG_TAG, "Start friend web server...");
            List<String> friendPeerCertificates = new ArrayList<String>();
            friendPeerCertificates.add(self.mPublicIdentity.mX509Certificate);
            friendPeerCertificates.add(otherFriendX509KeyMaterial.mCertificate);
            friendRequestHandler = new MockRequestHandler();
            friendWebServer = new WebServer(friendRequestHandler, friendX509KeyMaterial, friendPeerCertificates);
            try {
                friendWebServer.start();
            } catch (IOException e) {
                throw new Utils.ApplicationError(LOG_TAG, e);
            }

            // Test direct web request (not through Tor)
            // Repeat multiple times to exercise keep-alive connection
            String response;
            String expectedResponse = Json.toJson(selfRequestHandler.getMockStatus());
            for (int i = 0; i < 4; i++) {
                Log.addEntry(LOG_TAG, "Direct GET request from valid friend...");
                response = WebClient.makeGetRequest(
                        friendX509KeyMaterial,
                        self.mPublicIdentity.mX509Certificate,
                        WebClient.UNTUNNELED_REQUEST,
                        "127.0.0.1",
                        selfWebServer.getListeningPort(),
                        Protocol.PULL_STATUS_REQUEST_PATH);
                Protocol.validateStatus(Json.fromJson(response, Data.Status.class));
                if (!response.equals(expectedResponse)) {
                    throw new Utils.ApplicationError(LOG_TAG, "unexpected status response value");
                }
                Log.addEntry(LOG_TAG, "Direct POST request from valid friend...");
                WebClient.makeJsonPostRequest(
                        friendX509KeyMaterial,
                        self.mPublicIdentity.mX509Certificate,
                        WebClient.UNTUNNELED_REQUEST,
                        "127.0.0.1",
                        selfWebServer.getListeningPort(),
                        Protocol.PUSH_STATUS_REQUEST_PATH,
                        expectedResponse);
            }

            Log.addEntry(LOG_TAG, "Run self Tor...");
            List<TorWrapper.HiddenServiceAuth> selfHiddenServiceAuths = new ArrayList<TorWrapper.HiddenServiceAuth>();
            selfHiddenServiceAuths.add(
                    new TorWrapper.HiddenServiceAuth(
                            friendSelf.mPublicIdentity.mHiddenServiceHostname,
                            friendSelf.mPublicIdentity.mHiddenServiceAuthCookie));
            selfTor = new TorWrapper(
                    TorWrapper.Mode.MODE_RUN_SERVICES,
                    "runComponentTests-self",
                    selfHiddenServiceAuths,
                    selfHiddenServiceKeyMaterial,
                    selfWebServer.getListeningPort());
            selfTor.start();

            Log.addEntry(LOG_TAG, "Run friend Tor...");
            List<TorWrapper.HiddenServiceAuth> friendHiddenServiceAuths = new ArrayList<TorWrapper.HiddenServiceAuth>();
            friendHiddenServiceAuths.add(
                    new TorWrapper.HiddenServiceAuth(
                            self.mPublicIdentity.mHiddenServiceHostname,
                            self.mPublicIdentity.mHiddenServiceAuthCookie));
            friendTor = new TorWrapper(
                    TorWrapper.Mode.MODE_RUN_SERVICES,
                    "runComponentTests-friend",
                    friendHiddenServiceAuths,
                    friendHiddenServiceKeyMaterial,
                    friendWebServer.getListeningPort());
            friendTor.start();
            selfTor.start();
            selfTor.awaitStarted();
            friendTor.awaitStarted();

            // TODO: monitor publication state via Tor control interface?
            int publishWaitMilliseconds = 30000;
            Log.addEntry(LOG_TAG, String.format("Wait %d ms. while hidden service is published...", publishWaitMilliseconds));
            try {
                Thread.sleep(publishWaitMilliseconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < 4; i++) {
                Log.addEntry(LOG_TAG, "request from valid friend...");
                response = WebClient.makeGetRequest(
                        friendX509KeyMaterial,
                        self.mPublicIdentity.mX509Certificate,
                        friendTor.getSocksProxyPort(),
                        self.mPublicIdentity.mHiddenServiceHostname,
                        Protocol.WEB_SERVER_VIRTUAL_PORT,
                        Protocol.PULL_STATUS_REQUEST_PATH);
                Protocol.validateStatus(Json.fromJson(response, Data.Status.class));
                if (!response.equals(expectedResponse)) {
                    throw new Utils.ApplicationError(LOG_TAG, "unexpected status response value");
                }
            }

            Log.addEntry(LOG_TAG, "Request from invalid friend...");
            boolean failed = false;
            try {
                WebClient.makeGetRequest(
                        unfriendlyX509KeyMaterial,
                        self.mPublicIdentity.mX509Certificate,
                        friendTor.getSocksProxyPort(),
                        self.mPublicIdentity.mHiddenServiceHostname,
                        Protocol.WEB_SERVER_VIRTUAL_PORT,
                        Protocol.PULL_STATUS_REQUEST_PATH);
            } catch (Utils.ApplicationError e) {
                if (!e.getMessage().contains("No peer certificate")) {
                    throw e;
                }
                failed = true;
            }
            if (!failed) {
                throw new Utils.ApplicationError(LOG_TAG, "unexpected success");
            }

            // Re-run friend's Tor with an invalid hidden service auth cookie
            Log.addEntry(LOG_TAG, "Request from friend with invalid hidden service auth cookie...");
            friendTor.stop();
            friendHiddenServiceAuths = new ArrayList<TorWrapper.HiddenServiceAuth>();
            byte[] badAuthCookie = new byte[16]; // 128-bit value, as per spec
            new Random().nextBytes(badAuthCookie);
            friendHiddenServiceAuths.add(
                    new TorWrapper.HiddenServiceAuth(
                            self.mPublicIdentity.mHiddenServiceHostname,
                            Utils.encodeBase64(badAuthCookie).substring(0, 22)));
            friendTor = new TorWrapper(
                    TorWrapper.Mode.MODE_RUN_SERVICES,
                    "runComponentTests-friend",
                    friendHiddenServiceAuths,
                    friendHiddenServiceKeyMaterial,
                    friendWebServer.getListeningPort());
            friendTor.start();
            friendTor.awaitStarted();
            failed = false;
            try {
                response = WebClient.makeGetRequest(
                        friendX509KeyMaterial,
                        self.mPublicIdentity.mX509Certificate,
                        friendTor.getSocksProxyPort(),
                        self.mPublicIdentity.mHiddenServiceHostname,
                        Protocol.WEB_SERVER_VIRTUAL_PORT,
                        Protocol.PULL_STATUS_REQUEST_PATH);
            } catch (Utils.ApplicationError e) {
                if (!e.getMessage().contains("SOCKS4a connect failed")) {
                    throw e;
                }
                failed = true;
            }
            if (!failed) {
                throw new Utils.ApplicationError(LOG_TAG, "unexpected success");
            }

            Log.addEntry(LOG_TAG, "Component test run success");
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "Test failed");
        } finally {
            if (selfTor != null) {
                selfTor.stop();
            }
            if (friendTor != null) {
                friendTor.stop();
            }
            if (selfWebServer != null) {
                selfWebServer.stop();
            }
            if (selfRequestHandler != null) {
                selfRequestHandler.stop();
            }
            if (friendWebServer != null) {
                friendWebServer.stop();
            }
            if (friendRequestHandler != null) {
                friendRequestHandler.stop();
            }
        }
    }
}
