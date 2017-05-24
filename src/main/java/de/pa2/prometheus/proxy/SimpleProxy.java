package de.pa2.prometheus.proxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;

public class SimpleProxy {
	private static final Logger LOG = LoggerFactory.getLogger(SimpleProxy.class);

	private static class ProxyConfiguration {
		public static enum Mode {
			HTTP("http"), SSH_AND_HTTP("ssh+http");
			private String configurationValue;

			private Mode(String configurationValue) {
				this.configurationValue = configurationValue;
			}

			public String getConfigurationValue() {
				return configurationValue;
			}

			public static Mode byConfigurationValue(String configurationValue) {
				for (Mode m : Mode.values()) {
					if (m.getConfigurationValue().equalsIgnoreCase(configurationValue.trim())) {
						return m;
					}
				}
				return null;
			}
		}

		private Properties properties = null;

		public ProxyConfiguration(Properties properties) {
			super();
			this.properties = properties;
		}

		public String getUrl() {
			return this.properties.getProperty("url");
		}

		public URI getURI() {
			URI uri = null;
			try {
				uri = new URI(getUrl());
			} catch (Exception e) {
				LOG.error("could not create uri: {}", e.getMessage(), e);
			}
			return uri;
		}

		/**
		 * ssh only
		 * 
		 * @return
		 */
		public String getUser() {
			return this.properties.getProperty("user");
		}

		/**
		 * ssh only
		 * 
		 * @return
		 */
		public String getKey() {
			return this.properties.getProperty("key");
		}

		/**
		 * ssh only
		 * 
		 * @return
		 */
		public String getHost() {
			return this.properties.getProperty("host");
		}

		/**
		 * ssh only
		 * 
		 * @return
		 */
		public int getPort() {
			return Integer.parseInt(properties.getProperty("port", "22"));
		}

		public Mode getMode() {
			return Mode.byConfigurationValue(this.properties.getProperty("mode", "http"));
		}
	}

	private static ProxyConfiguration getConfiguration(File configDir, String configuration) {

		File cfg = new File(configDir, configuration);
		if (cfg.exists() && cfg.isDirectory()) {
			File cfgFile = new File(cfg, "configuration.properties");

			if (cfgFile.exists() && cfgFile.isFile()) {
				FileInputStream is = null;
				try {
					Properties properties = new Properties();
					is = new FileInputStream(cfgFile);
					properties.load(is);
					return new ProxyConfiguration(properties);
				} catch (IOException e) {
					LOG.error("could not read properties: {}", e.getMessage(), e);
				} finally {
					if (is != null) {
						try {
							is.close();
						} catch (IOException e) {
							LOG.error("could not close input stream: {}", e.getMessage(), e);
						}
					}
				}
			}
		}

		return null;
	}

	public static void main(String[] args) {
		String configParameter = args != null && args.length > 0 ? args[0] : "config";
		File configDir = new File(configParameter);

		if (configDir.exists() && configDir.isDirectory()) {
			LOG.info("using configuration from directory: {}", configDir.getAbsolutePath());
		} else {
			LOG.error("configuration directory dies not exist: {}", configDir.getAbsolutePath());
			System.exit(1);
		}
		VertxOptions options = new VertxOptions();
		options.setMaxEventLoopExecuteTime(Long.MAX_VALUE);
		Vertx vertx = Vertx.vertx(options);
		HttpServer server = vertx.createHttpServer();

		server.requestHandler(req -> {
			LOG.debug("requested uri: {}", req.uri());

			HttpServerResponse response = req.response();
			if (req.uri().endsWith("/metrics")) {
				String configuration = req.uri().substring(0, req.uri().length() - "/metrics".length());
				LOG.debug("using configuration: {}", configuration);

				ProxyConfiguration proxyConfiguration = getConfiguration(configDir, configuration);
				if (proxyConfiguration != null) {
					if (proxyConfiguration
							.getMode() == de.pa2.prometheus.proxy.SimpleProxy.ProxyConfiguration.Mode.HTTP) {
						URI uri = proxyConfiguration.getURI();
						HttpClient client = vertx.createHttpClient(new HttpClientOptions());
						HttpClientRequest clientRequest = client.request(req.method(), uri.getPort(), uri.getHost(),
								uri.getPath(), clientResponse -> {
									req.response().setChunked(true);
									req.response().setStatusCode(clientResponse.statusCode());
									req.response().headers().setAll(clientResponse.headers());
									clientResponse.handler(data -> {
										req.response().write(data);
									});
									clientResponse.endHandler((v) -> req.response().end());
								});
						clientRequest.setChunked(true);
						clientRequest.headers().setAll(req.headers());
						req.handler(data -> {
							clientRequest.write(data);
						});

						req.endHandler((v) -> clientRequest.end());

					} else if (proxyConfiguration
							.getMode() == de.pa2.prometheus.proxy.SimpleProxy.ProxyConfiguration.Mode.SSH_AND_HTTP) {
						// connect to remote server
						Session session = null;
						try {
							LOG.debug("connecting to {} via ssh", proxyConfiguration.getHost());
							JSch jsch = new JSch();
							jsch.addIdentity(proxyConfiguration.getKey());
							// final Session session =
							// jsch.getSession(proxyConfiguration.getUser(),
							// proxyConfiguration.getHost(),
							// proxyConfiguration.getPort());
							session = jsch.getSession(proxyConfiguration.getUser(), proxyConfiguration.getHost(),
									proxyConfiguration.getPort());
							session.setConfig("StrictHostKeyChecking", "no");
							LOG.debug("...");
							session.connect();
							LOG.debug("connected.");

							URI remoteUri = proxyConfiguration.getURI();
							int remotePort = remoteUri.getPort();
							String remoteHost = remoteUri.getHost();

							LOG.debug("forwarding remote port to local");
							int localPort = findRandomOpenPortOnAllLocalInterfaces();

							int assingedPort = session.setPortForwardingL(localPort, remoteHost, remotePort);

							// Thread.sleep(1000);
							// "::1"
							// "localhost"
							// "127.0.0.1"
							LOG.debug("forwarded remote port to local: {} =? {}", localPort, assingedPort);
							URI localUri = new URI(remoteUri.getScheme(), remoteUri.getUserInfo(), "localhost",
									localPort, remoteUri.getPath(), remoteUri.getQuery(), remoteUri.getFragment());

							LOG.debug("requesting local uri: {}", localUri);

							// HttpClient client = vertx.createHttpClient(new
							// HttpClientOptions());
							// HttpClientRequest clientRequest =
							// client.request(req.method(), localUri.getPort(),
							// localUri.getHost(), localUri.getPath(),
							// clientResponse -> {
							// LOG.debug("handle client response: {}",
							// clientResponse);
							// req.response().setChunked(true);
							// req.response().setStatusCode(clientResponse.statusCode());
							// req.response().headers().setAll(clientResponse.headers());
							// clientResponse.handler(data -> {
							// req.response().write(data);
							// });
							// clientResponse.endHandler((v) ->
							// req.response().end());
							// }
							//
							// );
							// clientRequest.setChunked(true);
							// clientRequest.headers().setAll(req.headers());
							// req.handler(data -> {
							// clientRequest.write(data);
							// });
							//
							// req.endHandler((v) -> {
							// clientRequest.end();
							// if (session != null && session.isConnected()) {
							// session.disconnect();
							// }
							// });

							URL url = new URL(localUri.toString());
							HttpURLConnection conn = (HttpURLConnection) url.openConnection();
							conn.setRequestMethod("GET");
							BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
							Map<String, List<String>> headers = conn.getHeaderFields();

							for (String headerName : headers.keySet()) {
								if (headerName != null) {
									req.response().putHeader(headerName, headers.get(headerName));
								}
							}
							StringBuilder buf = new StringBuilder();
							String line;

							while ((line = rd.readLine()) != null) {
								if (buf.length() > 0) {
									buf.append("\n");
								}
								buf.append(line);
							}
							response.setStatusCode(conn.getResponseCode());
							response.putHeader("content-type", conn.getContentType());
							response.setChunked(true);
							response.write(buf.toString());
							rd.close();
							response.end();

						} catch (JSchException | IOException | URISyntaxException e) {
							LOG.error("error in connection: " + e.getMessage(), e);
						} finally {
							if (session != null && session.isConnected()) {
								session.disconnect();
							}
						}
					} else {
						LOG.error("unhandled mode: {}", proxyConfiguration.getMode());
						response.setStatusCode(500);
						response.setChunked(true);
						response.write("unhandled mode: " + proxyConfiguration.getMode());
						response.end();

					}

				} else {
					LOG.error("configuration not found: {}", configuration);
					response.setStatusCode(404);
					response.setChunked(true);
					response.write("not found");
					response.end();
				}
			} else {
				LOG.error("not a metric request: {}", req.uri());
				response.setStatusCode(404);
				response.setChunked(true);
				response.write("not found");
				response.end();
			}
		});

		server.listen(18080);
	}

	private static Integer findRandomOpenPortOnAllLocalInterfaces() throws IOException {
		ServerSocket socket = null;
		try {
			socket = new ServerSocket(0);
			return socket.getLocalPort();
		} finally {
			if (socket != null) {
				socket.close();
			}
		}
	}
}
