/**************************************************
 * Android Web Server
 * Based on JavaLittleWebServer (2008)
 * <p/>
 * Copyright (c) Piotr Polak 2008-2017
 **************************************************/

package ro.polak.http;

import java.io.IOException;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

import ro.polak.http.configuration.ServerConfig;
import ro.polak.http.errorhandler.HttpErrorHandlerResolver;
import ro.polak.http.exception.AccessDeniedException;
import ro.polak.http.exception.MethodNotAllowedException;
import ro.polak.http.exception.NotFoundException;
import ro.polak.http.resource.provider.ResourceProvider;
import ro.polak.http.servlet.impl.HttpRequestImpl;
import ro.polak.http.servlet.impl.HttpResponseImpl;
import ro.polak.http.servlet.HttpServletRequest;
import ro.polak.http.servlet.factory.HttpServletRequestImplFactory;
import ro.polak.http.servlet.HttpServletResponse;
import ro.polak.http.servlet.factory.HttpServletResponseImplFactory;
import ro.polak.http.utilities.IOUtilities;

/**
 * Server thread.
 *
 * @author Piotr Polak piotr [at] polak [dot] ro
 * @since 200802
 */
public class ServerRunnable implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ServerRunnable.class.getName());

    private final ServerConfig serverConfig;
    private final Socket socket;
    private final HttpServletRequestImplFactory requestFactory;
    private final HttpServletResponseImplFactory responseFactory;
    private final HttpErrorHandlerResolver httpErrorHandlerResolver;
    private final PathHelper pathHelper;

    /**
     * Default constructor.
     *
     * @param socket
     * @param serverConfig
     * @param requestFactory
     * @param httpErrorHandlerResolver
     */
    public ServerRunnable(final Socket socket,
                          final ServerConfig serverConfig,
                          final HttpServletRequestImplFactory requestFactory,
                          final HttpServletResponseImplFactory responseFactory,
                          final HttpErrorHandlerResolver httpErrorHandlerResolver,
                          final PathHelper pathHelper) {
        this.socket = socket;
        this.serverConfig = serverConfig;
        this.requestFactory = requestFactory;
        this.responseFactory = responseFactory;
        this.httpErrorHandlerResolver = httpErrorHandlerResolver;
        this.pathHelper = pathHelper;
    }

    @Override
    public void run() {
        HttpResponseImpl response = null;

        try {
            try {
                response = responseFactory.createFromSocket(socket);
                HttpRequestImpl request = requestFactory.createFromSocket(socket);

                LOGGER.log(Level.INFO, "Handling request {0} {1}", new Object[]{
                        request.getMethod(), request.getRequestURI()
                });

                String requestedPath = request.getRequestURI();

                if (pathHelper.isPathContainingIllegalCharacters(requestedPath)) {
                    throw new AccessDeniedException();
                }

                validateRequest(request);

                setDefaultResponseHeaders(request, response);

                ResourceProvider resourceProvider = getResourceProvider(requestedPath);
                if (resourceProvider != null) {
                    resourceProvider.load(requestedPath, request, response);
                } else {
                    handleDirectoryIndex(response, request, requestedPath);
                }
            } catch (RuntimeException e) {
                if (response != null) {
                    httpErrorHandlerResolver.getHandler(e).serve(response);
                }

                throw e; // Make it logged by the main thread
            } finally {
                IOUtilities.closeSilently(socket);
            }
        } catch (IOException e) {
            LOGGER.log(Level.INFO, "Encountered IOException when handling request {0}", new Object[]{
                    e.getMessage()
            });
        }
    }

    private void handleDirectoryIndex(HttpResponseImpl response, HttpRequestImpl request,
                                      String requestedPath) throws IOException {
        DirectoryIndexDescriptor indexDescriptor = loadDirectoryIndexResource(requestedPath);
        if (indexDescriptor == null) {
            throw new NotFoundException();
        } else {
            if (!pathHelper.isDirectoryPath(requestedPath)) {
                sendRedirectToDirectorySlashedPath(response, requestedPath);
            } else {
                indexDescriptor.getResourceProvider().load(
                        indexDescriptor.getDirectoryPath(), request, response);
            }
        }
    }

    private void sendRedirectToDirectorySlashedPath(HttpResponseImpl response, String originalPath) throws IOException {
        response.setStatus(HttpServletResponse.STATUS_MOVED_PERMANENTLY);
        response.getHeaders().setHeader(Headers.HEADER_LOCATION, originalPath + "/");
        response.flush();
    }

    /**
     * Sets default response headers.
     *
     * @param request
     * @param response
     */
    private void setDefaultResponseHeaders(HttpRequestImpl request, HttpResponseImpl response) {
        boolean isKeepAlive = false;
        if (request.getHeaders().containsHeader(Headers.HEADER_CONNECTION)) {
            isKeepAlive = request.getHeaders().getHeader(Headers.HEADER_CONNECTION).equalsIgnoreCase("keep-alive");
        }

        response.setKeepAlive(isKeepAlive && serverConfig.isKeepAlive());
        response.getHeaders().setHeader(Headers.HEADER_SERVER, WebServer.SIGNATURE);
    }

    private DirectoryIndexDescriptor loadDirectoryIndexResource(String path) {
        String normalizedDirectoryPath = pathHelper.getNormalizedDirectoryPath(path);
        for (String index : serverConfig.getDirectoryIndex()) {
            String directoryIndexPath = normalizedDirectoryPath + index;
            ResourceProvider resourceProvider = getResourceProvider(directoryIndexPath);
            if (resourceProvider != null) {
                return new DirectoryIndexDescriptor(resourceProvider, directoryIndexPath);
            }
        }
        return null;
    }

    private ResourceProvider getResourceProvider(String path) {
        for (ResourceProvider resourceProvider : serverConfig.getResourceProviders()) {
            if (resourceProvider.canLoad(path)) {
                return resourceProvider;
            }
        }
        return null;
    }

    /**
     * Throws exception in case of invalid request.
     *
     * @param request
     */
    private void validateRequest(HttpServletRequest request) {
        if (!isMethodSupported(request.getMethod())) {
            throw new MethodNotAllowedException();
        }
    }

    /**
     * Tells whether the given HTTP method is supported.
     *
     * @param method
     * @return
     */
    private boolean isMethodSupported(String method) {
        for (String aMethod : serverConfig.getSupportedMethods()) {
            if (aMethod.equals(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns associated socket.
     *
     * @return
     */
    protected Socket getSocket() {
        return socket;
    }

    /**
     * Helper class describing directory index
     */
    private static class DirectoryIndexDescriptor {
        private ResourceProvider resourceProvider;
        private String directoryPath;

        public DirectoryIndexDescriptor(ResourceProvider resourceProvider, String indexFilePath) {
            this.resourceProvider = resourceProvider;
            this.directoryPath = indexFilePath;
        }

        public ResourceProvider getResourceProvider() {
            return resourceProvider;
        }

        public String getDirectoryPath() {
            return directoryPath;
        }
    }
}
