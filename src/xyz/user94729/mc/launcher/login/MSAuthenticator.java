/*
 * Copyright (C) 2021 user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package xyz.user94729.mc.launcher.login;

import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

import org.json.JSONObject;
import org.omegazero.common.logging.Logger;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import xyz.user94729.mc.launcher.AuthenticationException;
import xyz.user94729.mc.launcher.Util;
import xyz.user94729.mc.launcher.session.MSPlayerSession;
import xyz.user94729.mc.launcher.session.PlayerSession;

public class MSAuthenticator implements LoginManager {

	private static final Logger logger = Logger.create();

	private static final String REDIRECT_URL = "https://login.live.com/oauth20_desktop.srf";
	private static final String REDIRECT_URL_ENCODED = URLEncoder.encode(REDIRECT_URL, StandardCharsets.UTF_8);
	private static final String LOGIN_URL = "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code"
			+ "&scope=XboxLive.signin%20offline_access&redirect_uri=" + REDIRECT_URL_ENCODED;


	@Override
	public PlayerSession doLogin(PlayerSession psession, JPanel ui) throws IOException {
		if(psession == null){
			logger.info("Creating new session");
			psession = new MSPlayerSession();
		}
		MSPlayerSession session = (MSPlayerSession) psession;
		while(true){
			try{
				if(authChain(session))
					return session;
				else
					return null;
			}catch(AuthenticationException e){
				logger.warn("Some token seems to be invalid, resetting everything: ", e);
				session.msAccessToken = null;
				session.xblToken = null;
				session.xblUserHash = null;
				session.xstsToken = null;
				session.setAccessToken(null);
			}
		}
	}


	private static String loginPrompt() {
		JDialog jf = new JDialog();
		JProgressBar loadBar = new JProgressBar();
		loadBar.setBackground(Color.LIGHT_GRAY);
		loadBar.setForeground(Color.GREEN);
		loadBar.setBorder(null);
		loadBar.setMaximum(120);
		JFXPanel jfxPanel = new JFXPanel();
		jf.getContentPane().setPreferredSize(new Dimension(500, 600));
		jf.pack();
		jf.setResizable(false);
		jf.setLayout(null);
		jf.setLocationRelativeTo(null);
		jf.setModal(true);
		loadBar.setBounds(0, 0, jf.getContentPane().getWidth(), 5);
		jfxPanel.setBounds(0, 5, jf.getContentPane().getWidth(), jf.getContentPane().getHeight() - 5);
		jf.add(jfxPanel);
		jf.add(loadBar);
		loadBar.setValue(1);

		AtomicReference<String> authCodeRef = new AtomicReference<>();

		Platform.setImplicitExit(false);
		Platform.runLater(() -> {
			loadBar.setValue(3);
			WebView webView = new WebView();
			loadBar.setValue(12);
			webView.getEngine().load(LOGIN_URL);
			webView.getEngine().setJavaScriptEnabled(true);
			webView.setPrefWidth(500);
			webView.setPrefHeight(595);
			loadBar.setValue(14);

			webView.getEngine().getHistory().getEntries().addListener((ListChangeListener<WebHistory.Entry>) (c) -> {
				if(c.next() && c.wasAdded()){
					for(WebHistory.Entry entry : c.getAddedSubList()){
						if(entry.getUrl().startsWith(REDIRECT_URL + "?code=")){
							authCodeRef.set(entry.getUrl().substring(entry.getUrl().indexOf("=") + 1, entry.getUrl().indexOf("&")));
							jf.dispose();
							webView.getEngine().load(null);
						}
					}
				}
			});

			webView.getEngine().getLoadWorker().workDoneProperty().addListener(new ChangeListener<Number>(){
				@Override
				public void changed(ObservableValue<? extends Number> observableValue, Number oldValue, final Number newValue) {
					int percentage = newValue.intValue();
					loadBar.setValue(percentage + 20);
				}
			});

			jfxPanel.setScene(new Scene(webView));
			loadBar.setValue(20);
		});

		jf.setVisible(true);
		jf.dispose();
		return authCodeRef.get();
	}

	/**
	 * Performs the entire Microsoft account login chain from a login prompt to full minecraft profile for the given <b>session</b>. This method uses any tokens that may
	 * already be stored in the session.
	 * 
	 * @param session The session
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If any authentication fails
	 * @return <code>false</code> if the login was aborted by the user, <code>true</code> otherwise
	 */
	public static boolean authChain(MSPlayerSession session) throws IOException {
		if(session.msAccessToken == null){
			String[] tokens = null;
			while(tokens == null){
				if(session.msRefreshToken != null){
					logger.info("Attempting token refresh with existing refresh token");
					try{
						tokens = refreshMSAuthToken(session.msRefreshToken);
					}catch(AuthenticationException e){
						logger.info("Token refresh failed, discarding token: ", e);
						session.msRefreshToken = null;
					}
				}else{
					logger.info("Prompting user to sign in for MS auth token");
					String authCode = loginPrompt();
					if(authCode == null)
						return false;
					tokens = getMSAuthToken(authCode);
				}
			}
			session.msAccessToken = tokens[0];
			session.msRefreshToken = tokens[1];
		}
		if(session.xblToken == null){
			logger.info("Obtaining XBL token");
			String[] xblAuth = getXblAuth(session.msAccessToken);
			session.xblToken = xblAuth[0];
			session.xblUserHash = xblAuth[1];
		}
		if(session.xstsToken == null){
			logger.info("Obtaining XSTS token");
			session.xstsToken = getXstsToken(session.xblToken);
		}
		if(session.getAccessToken() == null){
			logger.info("Obtaining minecraft token");
			session.setAccessToken(getMCAccessToken(session.xstsToken, session.xblUserHash));
		}
		logger.info("Fetching minecraft profile");
		JSONObject mcProfile = getMCProfile(session.getAccessToken());
		session.setPlayerUUID(mcProfile.getString("id"));
		session.setPlayerName(mcProfile.getString("name"));
		return true;
	}


	/**
	 * Gets the Microsoft authentication and refresh token using a Microsoft authentication code returned by a login prompt
	 * (<code>https://login.live.com/oauth20_token.srf</code>). The application client ID is the Minecraft ID (<code>00000000402b5328</code>).
	 * 
	 * @param msAuthCode The Microsoft authentication code
	 * @return The Microsoft authentication and refresh token as the first and second element, respectively
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If authentication fails, likely due to an invalid token
	 * @see #getMSAuthTokens(String)
	 */
	public static String[] getMSAuthToken(String msAuthCode) throws IOException {
		return getMSAuthTokens("client_id=00000000402b5328&code=" + URLEncoder.encode(msAuthCode, StandardCharsets.UTF_8) + "&grant_type=authorization_code&redirect_uri="
				+ REDIRECT_URL_ENCODED);
	}

	/**
	 * Refreshes a Microsoft authentication token using a refresh token returned while creating the token using {@link #getMSAuthToken(String)}
	 * (<code>https://login.live.com/oauth20_token.srf</code>). The application client ID is the Minecraft ID (<code>00000000402b5328</code>).
	 * 
	 * @param refreshToken The refresh token
	 * @return The refreshed Microsoft authentication and refresh token as the first and second element, respectively
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If authentication fails, likely due to an invalid token
	 * @see #getMSAuthTokens(String)
	 */
	public static String[] refreshMSAuthToken(String refreshToken) throws IOException {
		return getMSAuthTokens("client_id=00000000402b5328&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
				+ "&grant_type=refresh_token&redirect_uri=" + REDIRECT_URL_ENCODED);
	}

	/**
	 * Makes a POST request to <code>https://login.live.com/oauth20_token.srf</code> with the given <code>application/x-www-form-urlencoded</code> <b>body</b> and extracts the
	 * <code>access_token</code> and <code>refresh_token</code> as the first and second element of the returned array, respectively.
	 * 
	 * @param body The body
	 * @return The access and refresh token
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If authentication fails, likely due to an invalid token
	 * @see #getMSAuthToken(String)
	 * @see #refreshMSAuthToken(String)
	 */
	public static String[] getMSAuthTokens(String body) throws IOException {
		HttpResponse<String> res = Util.post("https://login.live.com/oauth20_token.srf", "application/x-www-form-urlencoded", body);
		JSONObject json = getMSAuthJSON(res);
		return new String[] { json.getString("access_token"), json.getString("refresh_token") };
	}

	/**
	 * Gets the Xbox Live (XBL) token and user hash using a Microsoft authentication token returned by {@link #getMSAuthToken(String)}
	 * (<code>https://user.auth.xboxlive.com/user/authenticate</code>).
	 * 
	 * @param msAuthToken The Microsoft authentication token
	 * @return An array where the first element is the XBL token and the second element is the user hash
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If authentication fails, likely due to an invalid token
	 */
	public static String[] getXblAuth(String msAuthToken) throws IOException {
		HttpResponse<String> res = Util.postJson("https://user.auth.xboxlive.com/user/authenticate",
				"{\"Properties\": {\"AuthMethod\": \"RPS\",\"SiteName\": \"user.auth.xboxlive.com\",\"RpsTicket\": \"d=" + msAuthToken
						+ "\"},\"RelyingParty\": \"http://auth.xboxlive.com\",\"TokenType\": \"JWT\"}");
		JSONObject json = getMSAuthJSON(res);
		String xblToken = json.getString("Token");
		String uhs = ((JSONObject) json.getJSONObject("DisplayClaims").getJSONArray("xui").get(0)).getString("uhs");
		return new String[] { xblToken, uhs };
	}

	/**
	 * Gets the XSTS token using a Xbox Live (XBL) token returned by {@link #getXblToken(String)} (<code>https://xsts.auth.xboxlive.com/xsts/authorize</code>).
	 * 
	 * @param xblToken The XBL token
	 * @return The XSTS token
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If authentication fails, likely due to an invalid token
	 */
	public static String getXstsToken(String xblToken) throws IOException {
		HttpResponse<String> res = Util.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", "{\"Properties\": {\"SandboxId\": \"RETAIL\",\"UserTokens\": [\"" + xblToken
				+ "\"]},\"RelyingParty\": \"rp://api.minecraftservices.com/\",\"TokenType\": \"JWT\"}");
		JSONObject json = getMSAuthJSON(res);
		return json.getString("Token");
	}

	/**
	 * Gets the Minecraft access token using a XSTS token and XBL user hash (<code>https://api.minecraftservices.com/authentication/login_with_xbox</code>).
	 * 
	 * @param xstsToken   The XSTS token, for example returned by {@link #getXstsToken(String)}
	 * @param xblUserHash The XBL user hash, for example returned by {@link #getXblAuth(String)}
	 * @return The Minecraft access token
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If authentication fails, likely due to an invalid token
	 */
	public static String getMCAccessToken(String xstsToken, String xblUserHash) throws IOException {
		HttpResponse<String> res = Util.postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
				"{\"identityToken\": \"XBL3.0 x=" + xblUserHash + ";" + xstsToken + "\"}");
		JSONObject json = getMSAuthJSON(res);
		return json.getString("access_token");
	}

	/**
	 * Gets the Minecraft profile associated with a Minecraft access token (<code>https://api.minecraftservices.com/minecraft/profile</code>).
	 * 
	 * @param accessToken The Minecraft access token
	 * @return The JSON object returned by the API
	 * @throws IOException             If an IO error occurs
	 * @throws AuthenticationException If authentication fails, likely due to an invalid token
	 */
	public static JSONObject getMCProfile(String accessToken) throws IOException {
		try{
			HttpRequest request = HttpRequest.newBuilder(new URI("https://api.minecraftservices.com/minecraft/profile")).header("Authorization", "Bearer " + accessToken).GET()
					.build();
			HttpResponse<String> res = HttpClient.newBuilder().build().send(request, HttpResponse.BodyHandlers.ofString());
			if(res.statusCode() == 401)
				throw new AuthenticationException("Invalid access token");
			return new JSONObject(res.body());
		}catch(InterruptedException | URISyntaxException e){
			throw new RuntimeException(e);
		}
	}


	private static JSONObject getMSAuthJSON(HttpResponse<String> res) throws IOException {
		int status = res.statusCode();
		if(status == 200)
			return new JSONObject(res.body());
		else if(status == 400 || status == 401){
			try{
				JSONObject errjson = new JSONObject(res.body());
				throw new AuthenticationException(
						"MS Authentication failed (HTTP status " + status + "): " + errjson.getString("error") + ": " + errjson.getString("error_description"));
			}catch(org.json.JSONException e){
				IOException ne = new AuthenticationException("MS Authentication failed (HTTP status " + status + ")");
				ne.addSuppressed(e);
				throw ne;
			}
		}else
			throw new IOException("Non-200 status code: " + status);
	}


	@Override
	public String toString() {
		return "Microsoft login (default since 2021)";
	}
}
