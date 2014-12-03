package mediabrowser.apiinteraction.connectionmanager;

import mediabrowser.apiinteraction.*;
import mediabrowser.apiinteraction.connect.ConnectService;
import mediabrowser.apiinteraction.device.IDevice;
import mediabrowser.apiinteraction.discovery.IServerLocator;
import mediabrowser.apiinteraction.http.HttpHeaders;
import mediabrowser.apiinteraction.http.HttpRequest;
import mediabrowser.apiinteraction.http.IAsyncHttpClient;
import mediabrowser.apiinteraction.network.INetworkConnection;
import mediabrowser.model.apiclient.*;
import mediabrowser.model.connect.*;
import mediabrowser.model.dto.IHasServerId;
import mediabrowser.model.dto.UserDto;
import mediabrowser.model.extensions.IntHelper;
import mediabrowser.model.extensions.StringHelper;
import mediabrowser.model.logging.ILogger;
import mediabrowser.model.serialization.IJsonSerializer;
import mediabrowser.model.session.ClientCapabilities;
import mediabrowser.model.system.PublicSystemInfo;
import mediabrowser.model.system.SystemInfo;
import mediabrowser.model.users.AuthenticationResult;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ConnectionManager implements IConnectionManager {

    private ICredentialProvider _credentialProvider;
    private INetworkConnection _networkConnectivity;
    protected ILogger logger;
    private IServerLocator _serverDiscovery;
    protected IAsyncHttpClient httpClient;

    private HashMap<String, ApiClient> apiClients = new HashMap<String, ApiClient>();
    protected IJsonSerializer jsonSerializer;

    protected String applicationName;
    protected String applicationVersion;
    protected IDevice device;
    protected ClientCapabilities clientCapabilities;
    protected ApiEventListener apiEventListener;

    private ConnectService connectService;
    private ConnectUser connectUser;

    public ConnectionManager(ICredentialProvider credentialProvider,
                             INetworkConnection networkConnectivity,
                             IJsonSerializer jsonSerializer,
                             ILogger logger,
                             IServerLocator serverDiscovery,
                             IAsyncHttpClient httpClient,
                             String applicationName,
                             String applicationVersion,
                             IDevice device,
                             ClientCapabilities clientCapabilities,
                             ApiEventListener apiEventListener) {

        _credentialProvider = credentialProvider;
        _networkConnectivity = networkConnectivity;
        this.logger = logger;
        _serverDiscovery = serverDiscovery;
        this.httpClient = httpClient;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
        this.device = device;
        this.clientCapabilities = clientCapabilities;
        this.apiEventListener = apiEventListener;
        this.jsonSerializer = jsonSerializer;

        connectService = new ConnectService(jsonSerializer, logger, httpClient, applicationName, applicationVersion);

        device.getResumeFromSleepObservable().addObserver(new Observer() {

            @Override
            public void update(Observable observable, Object o)
            {
                WakeAllServers();
            }

        });
    }

    private boolean webSocketEnabled = true;
    @Override
    public void setWebSocketEnabled(boolean enabled) {
        webSocketEnabled = enabled;
    }

    private boolean reportCapabilities = true;
    @Override
    public void setReportCapabilitiesEnabled(boolean enabled) {
        reportCapabilities = enabled;
    }

    @Override
    public ApiClient GetApiClient(IHasServerId item) {

        return GetApiClient(item.getServerId());
    }

    @Override
    public ApiClient GetApiClient(String serverId) {

        return apiClients.get(serverId);
    }

    private void OnConnectUserSignIn(ConnectUser user){

        connectUser = user;
    }

    private void OnFailedConnection(Response<ConnectionResult> response){

        logger.Debug("No server available");

        ConnectionResult result = new ConnectionResult();
        result.setState(ConnectionState.Unavailable);
        result.setConnectUser(connectUser);
        response.onResponse(result);
    }

    private void OnFailedConnection(Response<ConnectionResult> response, ArrayList<ServerInfo> servers){

        logger.Debug("No server available");

        ConnectionResult result = new ConnectionResult();

        if (servers.size() == 0 && connectUser == null){
            result.setState(ConnectionState.ConnectSignIn);
        }
        else{
            result.setState(ConnectionState.ServerSelection);
        }

        result.setServers(new ArrayList<ServerInfo>());
        result.getServers().addAll(servers);

        result.setConnectUser(connectUser);

        response.onResponse(result);
    }

    @Override
    public void Connect(final Response<ConnectionResult> response) {

        logger.Debug("Entering initial connection workflow");

        GetAvailableServers(new GetAvailableServersResponse(logger, this, response));
    }

    void Connect(final ArrayList<ServerInfo> servers, final Response<ConnectionResult> response){

        // Sort by last date accessed, descending
        Collections.sort(servers, new ServerInfoDateComparator());

        if (servers.size() == 0){

            OnFailedConnection(response, servers);
            return;
        }

        if (servers.size() == 1)
        {
            Connect(servers.get(0), new Response<ConnectionResult>() {

                @Override
                public void onResponse(ConnectionResult result) {

                    if (result.getState() == ConnectionState.Unavailable) {
                        result.setState((result.getConnectUser() == null ? ConnectionState.ConnectSignIn : ConnectionState.ServerSelection));
                    }
                    response.onResponse(result);
                }
            });
            return;
        }

        // Check the first server for a saved access token
        ServerInfo firstServer = servers.get(0);
        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(firstServer.getAccessToken()))
        {
            OnFailedConnection(response, servers);
            return;
        }

        Connect(firstServer, new Response<ConnectionResult>() {

            @Override
            public void onResponse(ConnectionResult result) {

                if (result.getState() == ConnectionState.SignedIn) {
                    response.onResponse(result);
                } else {
                    OnFailedConnection(response, servers);
                }
            }
        });
    }

    @Override
    public void Connect(final ServerInfo server,
                        final Response<ConnectionResult> response) {

        Connect(server, true, true, response);
    }

    private void Connect(final ServerInfo server,
                        final boolean enableWakeOnLan,
                        final boolean enableLocalRetry,
                        final Response<ConnectionResult> response) {

        boolean isLocalhost = !tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getLocalAddress()) &&
                IsLocalHost(server.getLocalAddress());

        // Try connect locally if there's a local address,
        // and we're either on localhost or the device has a local connection
        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getLocalAddress()) &&
                (isLocalhost) || _networkConnectivity.getNetworkStatus().GetIsLocalNetworkAvailable())
        {
            final boolean retryLocal = enableLocalRetry && !isLocalhost;

            if (enableWakeOnLan && !isLocalhost){
                // Kick this off
                WakeServer(server, new EmptyResponse());
            }
            final long wakeTime = new Date().getTime();

            TryConnect(server.getLocalAddress(), new Response<PublicSystemInfo>(){

                @Override
                public void onResponse(PublicSystemInfo result) {

                    ConnectToFoundServer(server, result, ConnectionMode.Local, response);
                }

                @Override
                public void onError(Exception ex) {

                    if (retryLocal){

                        long sleepTime = 10000 - (new Date().getTime() - wakeTime);

                        if (sleepTime > 0){
                            try {
                                Thread.sleep(sleepTime, 0);
                            } catch (InterruptedException e) {
                                logger.ErrorException("Error in Thread.Sleep", e);
                            }
                        }

                        // Try local connection again
                        Connect(server, false, false, response);
                    }
                    else{
                        // No local connection available
                        TryConnectToRemoteAddress(server, response);
                    }
                }
            });

            return;
        }

        TryConnectToRemoteAddress(server, response);
    }

    private void TryConnectToRemoteAddress(final ServerInfo server,
                                           final Response<ConnectionResult> response){

        // If local connection is unavailable, try to connect to the remote address
        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getRemoteAddress()))
        {
            TryConnect(server.getRemoteAddress(), new Response<PublicSystemInfo>(){

                @Override
                public void onResponse(PublicSystemInfo result) {

                    ConnectToFoundServer(server, result, ConnectionMode.Remote, response);
                }

                @Override
                public void onError(Exception ex) {

                    // Unable to connect
                    OnFailedConnection(response);
                }
            });
        }
        else{
            OnFailedConnection(response);
        }
    }

    private void ConnectToFoundServer(final ServerInfo server,
                                     final PublicSystemInfo systemInfo,
                                     final ConnectionMode connectionMode,
                                     final Response<ConnectionResult> response) {

        final ServerCredentials credentials = _credentialProvider.GetCredentials();

        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(credentials.getConnectAccessToken()) &&
                !tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getExchangeToken()))
        {
            EnsureConnectUser(credentials, new EmptyResponse(){

                @Override
                public void onResponse() {

                    AddAuthenticationInfoFromConnect(server, connectionMode, credentials, new EmptyResponse() {

                        @Override
                        public void onResponse() {

                            AfterConnectValidated(server, credentials, systemInfo, connectionMode, true, response);
                        }
                    });
                }

            });
        }
        else{

            AfterConnectValidated(server, credentials, systemInfo, connectionMode, true, response);
        }
    }

    private void AddAuthenticationInfoFromConnect(final ServerInfo server,
                                                  ConnectionMode connectionMode,
                                                  ServerCredentials credentials,
                                                  final EmptyResponse response){

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getExchangeToken())) {
            throw new IllegalArgumentException("server");
        }

        logger.Debug("Adding authentication info from Connect");

        String url = server.GetAddress(connectionMode);

        url += "/mediabrowser/Connect/Exchange?format=json&ConnectUserId=" + credentials.getConnectUserId();

        HttpRequest request = new HttpRequest();
        request.setUrl(url);
        request.setMethod("GET");

        request.getRequestHeaders().put("X-MediaBrowser-Token", server.getExchangeToken());

        httpClient.Send(request, new Response<String>(){

            @Override
            public void onResponse(String jsonResponse) {

                ConnectAuthenticationExchangeResult obj = jsonSerializer.DeserializeFromString(jsonResponse, ConnectAuthenticationExchangeResult.class);

                server.setUserId(obj.getLocalUserId());
                server.setAccessToken(obj.getAccessToken());
                response.onResponse();
            }

            @Override
            public void onError(Exception ex) {

                response.onResponse();
            }
        });
    }

    private void AfterConnectValidated(final ServerInfo server,
                                       final ServerCredentials credentials,
                                       final PublicSystemInfo systemInfo,
                                       final ConnectionMode connectionMode,
                                       boolean verifyLocalAuthentication,
                                       final Response<ConnectionResult> response){

        if (verifyLocalAuthentication && !tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()))
        {
            ValidateAuthentication(server, connectionMode, new EmptyResponse(){

                @Override
                public void onResponse() {

                    AfterConnectValidated(server, credentials, systemInfo, connectionMode, false, response);
                }

                @Override
                public void onError(Exception ex) {

                    response.onError(ex);
                }
            });

            return;
        }

        server.ImportInfo(systemInfo);
        server.setDateLastAccessed(new Date());
        credentials.AddOrUpdateServer(server);
        _credentialProvider.SaveCredentials(credentials);

        ConnectionResult result = new ConnectionResult();

        result.setApiClient(GetOrAddApiClient(server, connectionMode));
        result.setState(tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()) ?
                ConnectionState.ServerSignIn :
                ConnectionState.SignedIn);

        if (result.getState() == ConnectionState.SignedIn)
        {
            EnsureWebSocketIfConfigured(result.getApiClient());
        }

        result.getServers().add(server);
        result.getApiClient().EnableAutomaticNetworking(server, connectionMode, _networkConnectivity);

        response.onResponse(result);
    }

    @Override
    public void Connect(final String address, final Response<ConnectionResult> response) {

        final String normalizeAddress = NormalizeAddress(address);

        logger.Debug("Attempting to connect to server at %s", address);

        TryConnect(normalizeAddress, new Response<PublicSystemInfo>(){

            @Override
            public void onResponse(PublicSystemInfo result) {

                ServerInfo server = new ServerInfo();

                server.setManualAddress(normalizeAddress);
                server.setLastConnectionMode(ConnectionMode.Manual);
                server.ImportInfo(result);

                Connect(server, response);
            }

            @Override
            public void onError(Exception ex) {

                OnFailedConnection(response);
            }
        });
    }

    private boolean IsLocalHost(String address)
    {
        return StringHelper.IndexOfIgnoreCase(address, "localhost") != -1 ||
                StringHelper.IndexOfIgnoreCase(address, "/127.") != -1;
    }

    @Override
    public void Logout(final EmptyResponse response) {

        logger.Debug("Logging out of all servers");

        LogoutAll(new EmptyResponse() {

            private void OnSuccessOrFail() {

                logger.Debug("Updating saved credentials for all servers");
                ServerCredentials credentials = _credentialProvider.GetCredentials();

                ArrayList<ServerInfo> servers = new ArrayList<ServerInfo>();

                for (ServerInfo server : credentials.getServers()) {

                    if (server.getUserLinkType() == null ||
                            server.getUserLinkType() != UserLinkType.Guest){

                        server.setAccessToken(null);
                        server.setExchangeToken(null);
                        server.setUserId(null);
                        servers.add(server);
                    }
                }

                credentials.setServers(servers);
                _credentialProvider.SaveCredentials(credentials);

                response.onResponse();
            }

            @Override
            public void onResponse() {
                OnSuccessOrFail();
            }

            @Override
            public void onError(Exception ex) {
                OnSuccessOrFail();
            }
        });
    }

    private Observable connectedObservable = new Observable();
    @Override
    public Observable getConnectedObservable() {
        return connectedObservable;
    }

    private void ValidateAuthentication(final ServerInfo server, ConnectionMode connectionMode, final EmptyResponse response)
    {
        final String url = server.GetAddress(connectionMode);

        HttpHeaders headers = new HttpHeaders();
        headers.SetAccessToken(server.getAccessToken());

        final HttpRequest request = new HttpRequest();
        request.setUrl(url + "/mediabrowser/system/info?format=json");
        request.setMethod("GET");
        request.setRequestHeaders(headers);

        Response<String> stringResponse = new Response<String>(){

            @Override
            public void onResponse(String jsonResponse) {

                SystemInfo obj = jsonSerializer.DeserializeFromString(jsonResponse, SystemInfo.class);
                server.ImportInfo(obj);

                if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getUserId()))
                {
                    request.setUrl(url + "/mediabrowser/users/" + server.getUserId() + "?format=json");

                    httpClient.Send(request, new Response<String>(){

                        @Override
                        public void onResponse(String stringResponse) {

                            UserDto user = jsonSerializer.DeserializeFromString(stringResponse, UserDto.class);
                            OnLocalUserSignIn(user);
                            response.onResponse();
                        }
                        @Override
                        public void onError(Exception ex) {

                            server.setUserId(null);
                            server.setAccessToken(null);
                            response.onResponse();
                        }
                    });
                }
                else {
                    response.onResponse();
                }
            }

            @Override
            public void onError(Exception ex) {

                server.setUserId(null);
                server.setAccessToken(null);
                response.onResponse();
            }
        };

        httpClient.Send(request, stringResponse);
    }

    private void TryConnect(String url, final Response<PublicSystemInfo> response)
    {
        url += "/mediabrowser/system/info/public?format=json";

        HttpRequest request = new HttpRequest();
        request.setUrl(url);
        request.setMethod("GET");

        httpClient.Send(request, new SerializedResponse<PublicSystemInfo>(response, jsonSerializer, PublicSystemInfo.class));
    }

    protected ApiClient InstantiateApiClient(String serverAddress) {

        return new ApiClient(httpClient,
                jsonSerializer,
                logger,
                serverAddress,
                applicationName,
                device,
                applicationVersion,
                apiEventListener,
                clientCapabilities);
    }

    private ApiClient GetOrAddApiClient(ServerInfo server, ConnectionMode connectionMode)
    {
        ApiClient apiClient = apiClients.get(server.getId());

        if (apiClient == null){

            String address = server.GetAddress(connectionMode);

            apiClient = InstantiateApiClient(address);

            apiClients.put(server.getId(), apiClient);

            final ApiClient finalApiClient = apiClient;

            apiClient.getAuthenticatedObservable().addObserver(new Observer() {

                @Override
                public void update(Observable observable, Object o)
                {
                    OnAuthenticated(finalApiClient, (AuthenticationResult) o, true);
                }
            });
        }

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()))
        {
            apiClient.ClearAuthenticationInfo();
        }
        else
        {
            apiClient.SetAuthenticationInfo(server.getAccessToken(), server.getUserId());
        }

        return apiClient;
    }

    private void EnsureWebSocketIfConfigured(ApiClient apiClient)
    {
        if (webSocketEnabled){
            apiClient.OpenWebSocket();
        }
    }

    private void OnAuthenticated(final ApiClient apiClient,
                                 final AuthenticationResult result,
                                 final boolean saveCredentials)
    {
        logger.Debug("Updating credentials after local authentication");

        apiClient.GetSystemInfoAsync(new Response<SystemInfo>() {

            @Override
            public void onResponse(SystemInfo info) {

                ServerInfo server = apiClient.getServerInfo();
                server.ImportInfo(info);

                ServerCredentials credentials = _credentialProvider.GetCredentials();

                server.setDateLastAccessed(new Date());

                if (saveCredentials)
                {
                    server.setUserId(result.getUser().getId());
                    server.setAccessToken(result.getAccessToken());
                }
                else {
                    server.setUserId(null);
                    server.setAccessToken(null);
                }

                credentials.AddOrUpdateServer(server);
                _credentialProvider.SaveCredentials(credentials);

                EnsureWebSocketIfConfigured(apiClient);

                OnLocalUserSignIn(result.getUser());
            }
        });
    }

    private void OnLocalUserSignIn(UserDto user)
    {

    }

    public void GetAvailableServers(final Response<ArrayList<ServerInfo>> response)
    {
        NetworkStatus networkInfo = _networkConnectivity.getNetworkStatus();

        logger.Debug("Getting saved servers via credential provider");
        final ServerCredentials credentials = _credentialProvider.GetCredentials();

        final int numTasks = 2;
        final int[] numTasksCompleted = {0};

        Response<ArrayList<ServerInfo>> findServersResponse = new Response<ArrayList<ServerInfo>>(){

            private void OnAny(ArrayList<ServerInfo> foundServers){

                synchronized (credentials){

                    numTasksCompleted[0]++;

                    OnGetServerResponse(credentials, foundServers, false, response, numTasksCompleted[0] >= numTasks);
                }
            }

            @Override
            public void onResponse(ArrayList<ServerInfo> response) {
                OnAny(response);
            }

            @Override
            public void onError(Exception ex) {

                OnAny(new ArrayList<ServerInfo>());
            }
        };

        if (networkInfo.GetIsLocalNetworkAvailable())
        {
            logger.Debug("Scanning network for local servers");

            FindServers(findServersResponse);
        }
        else {
            findServersResponse.onError(null);
        }

        EmptyResponse connectServersResponse = new EmptyResponse(){

            private void OnAny(ConnectUserServer[] foundServers){

                synchronized (credentials){

                    numTasksCompleted[0]++;

                    OnGetServerResponse(credentials, ConvertServerList(foundServers), true, response, numTasksCompleted[0] >= numTasks);
                }
            }

            @Override
            public void onResponse() {

                logger.Debug("Getting connect servers");

                connectService.GetServers(credentials.getConnectUserId(), credentials.getConnectAccessToken(), new Response<ConnectUserServer[]>(){

                    @Override
                    public void onResponse(ConnectUserServer[] response) {

                        OnAny(response);
                    }

                    @Override
                    public void onError(Exception ex) {

                        OnAny(new ConnectUserServer[]{});
                    }
                });
            }

            @Override
            public void onError(Exception ex) {

                OnAny(new ConnectUserServer[]{});
            }
        };

        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(credentials.getConnectAccessToken()))
        {
            logger.Debug("Getting server list from Connect");

            EnsureConnectUser(credentials, connectServersResponse);
        }
        else{
            connectServersResponse.onError(null);
        }
    }

    private void EnsureConnectUser(ServerCredentials credentials, final EmptyResponse response){

        if (connectUser != null && StringHelper.EqualsIgnoreCase(connectUser.getId(), credentials.getConnectUserId()))
        {
            response.onResponse();
            return;
        }

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(credentials.getConnectUserId()) || tangible.DotNetToJavaStringHelper.isNullOrEmpty(credentials.getConnectAccessToken()))
        {
            response.onResponse();
            return;
        }

        this.connectUser = null;

        ConnectUserQuery query = new ConnectUserQuery();

        query.setId(credentials.getConnectUserId());

        connectService.GetConnectUser(query, credentials.getConnectAccessToken(), new Response<ConnectUser>(){

            @Override
            public void onResponse(ConnectUser user) {

                OnConnectUserSignIn(user);
                response.onResponse();
            }

            @Override
            public void onError(Exception ex) {

                response.onResponse();
            }
        });
    }

    private void OnGetServerResponse(ServerCredentials credentials,
                                     ArrayList<ServerInfo> foundServers,
                                     boolean cleanServers,
                                     Response<ArrayList<ServerInfo>> response,
                                     boolean isComplete){

        for(ServerInfo newServer : foundServers){

            credentials.AddOrUpdateServer(newServer);
        }

        if (cleanServers){

            ArrayList<ServerInfo> cleanList = new ArrayList<ServerInfo>();
            ArrayList<ServerInfo> existing = credentials.getServers();

            for(ServerInfo server : existing){

                if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getExchangeToken()))
                {
                    cleanList.add(server);
                    continue;
                }

                boolean found = false;

                for(ServerInfo connectServer : foundServers){

                    if (StringHelper.EqualsIgnoreCase(server.getId(), connectServer.getId())){
                        found = true;
                        break;
                    }
                }

                if (found)
                {
                    cleanList.add(server);
                    continue;
                }
                else{
                    logger.Debug("Dropping server "+server.getName()+" - "+server.getId()+" because it's no longer in the user's Connect profile.");
                }
            }

            credentials.setServers(cleanList);
        }

        if (isComplete){
            _credentialProvider.SaveCredentials(credentials);

            ArrayList<ServerInfo> clone = new ArrayList<ServerInfo>();
            clone.addAll(credentials.getServers());
            response.onResponse(clone);
        }
    }

    private ArrayList<ServerInfo> ConvertServerList(ConnectUserServer[] userServers){

        ArrayList<ServerInfo> servers = new ArrayList<ServerInfo>();

        for(ConnectUserServer userServer : userServers){

            ServerInfo server = new ServerInfo();

            server.setExchangeToken(userServer.getAccessKey());
            server.setId(userServer.getSystemId());
            server.setName(userServer.getName());
            server.setLocalAddress(userServer.getLocalAddress());
            server.setRemoteAddress(userServer.getUrl());

            if (StringHelper.EqualsIgnoreCase(userServer.getUserType(), "guest"))
            {
                server.setUserLinkType(UserLinkType.Guest);
            }
            else{
                server.setUserLinkType(UserLinkType.LinkedUser);
            }

            servers.add(server);
        }

        return servers;
    }

    protected void FindServers(final Response<ArrayList<ServerInfo>> response)
    {
        FindServersInternal(response);
    }

    protected void FindServersInternal(final Response<ArrayList<ServerInfo>> response)
    {
        _serverDiscovery.FindServers(1500, new Response<ArrayList<ServerDiscoveryInfo>>(){

            @Override
            public void onResponse(ArrayList<ServerDiscoveryInfo> foundServers) {

                ArrayList<ServerInfo> servers = new ArrayList<ServerInfo>();

                for (int i=0; i< foundServers.size(); i++) {

                    ServerInfo server = new ServerInfo();
                    ServerDiscoveryInfo foundServer = foundServers.get(i);

                    server.setId(foundServer.getId());
                    server.setLocalAddress(foundServer.getAddress());
                    server.setName(foundServer.getName());

                    server.setManualAddress(ConvertEndpointAddressToManualAddress(foundServer));

                    servers.add(server);
                }

                response.onResponse(servers);
            }

            @Override
            public void onError(Exception ex) {

                ArrayList<ServerInfo> servers = new ArrayList<ServerInfo>();

                response.onResponse(servers);
            }
        });
    }

    private String ConvertEndpointAddressToManualAddress(ServerDiscoveryInfo info)
    {
        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(info.getAddress()) && !tangible.DotNetToJavaStringHelper.isNullOrEmpty(info.getEndpointAddress()))
        {
            String address = info.getEndpointAddress().split(":")[0];

            // Determine the port, if any
            String[] parts = info.getAddress().split(":");
            if (parts.length > 1)
            {
                String portString = parts[parts.length-1];

                int port = 0;
                tangible.RefObject<Integer> tempRef_expected = new tangible.RefObject<Integer>(port);
                if (IntHelper.TryParseCultureInvariant(portString, tempRef_expected))
                {
                    address += ":" + portString;
                }
            }

            return NormalizeAddress(address);
        }

        return null;
    }

    private void WakeAllServers()
    {
        logger.Debug("Waking all servers");

        for(ServerInfo server : _credentialProvider.GetCredentials().getServers()){

            WakeServer(server, new EmptyResponse());
        }
    }

    private void WakeServer(ServerInfo info, final EmptyResponse response)
    {
        logger.Debug("Waking server: %s, Id: %s", info.getName(), info.getId());

        ArrayList<WakeOnLanInfo> wakeList = info.getWakeOnLanInfos();

        final int count = wakeList.size();

        if (count == 0){
            logger.Debug("Server %s has no saved wake on lan profiles", info.getName());
            response.onResponse();
            return;
        }

        final ArrayList<EmptyResponse> doneList = new ArrayList<EmptyResponse>();

        for(WakeOnLanInfo wakeOnLanInfo : wakeList){

            WakeServer(wakeOnLanInfo, new WakeServerResponse(doneList, response));
        }
    }

    private void WakeServer(WakeOnLanInfo info, EmptyResponse response) {

        _networkConnectivity.SendWakeOnLan(info.getMacAddress(), info.getPort(), response);
    }

    private String NormalizeAddress(String address) throws IllegalArgumentException {

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(address))
        {
            throw new IllegalArgumentException("address");
        }

        if (StringHelper.IndexOfIgnoreCase(address, "http") == -1)
        {
            address = "http://" + address;
        }

        return address;
    }

    private void LogoutAll(final EmptyResponse response){

        Object[] clientList = apiClients.values().toArray();

        final int count = clientList.length;

        if (count == 0){
            response.onResponse();
            return;
        }

        final ArrayList<EmptyResponse> doneList = new ArrayList<EmptyResponse>();

        for(Object clientObj : clientList){

            ApiClient client = (ApiClient)clientObj;

            if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(client.getAccessToken()))
            {
                synchronized (doneList) {

                    doneList.add(new EmptyResponse());

                    if (doneList.size() >= count) {
                        response.onResponse();
                    }
                }
            }

            client.Logout(new EmptyResponse() {

                @Override
                public void onResponse() {

                    synchronized (doneList) {

                        doneList.add(new EmptyResponse());

                        if (doneList.size() >= count) {
                            response.onResponse();
                        }

                    }

                }

                @Override
                public void onError(Exception ex) {

                    onResponse();
                }

            });
        }
    }

    public void LoginToConnect(String username, String password, final EmptyResponse response) throws UnsupportedEncodingException, NoSuchAlgorithmException {

        connectService.Authenticate(username, password, new Response<ConnectAuthenticationResult>() {

            @Override
            public void onResponse(ConnectAuthenticationResult result) {

                ServerCredentials credentials = _credentialProvider.GetCredentials();

                credentials.setConnectAccessToken(result.getAccessToken());
                credentials.setConnectUserId(result.getUser().getId());

                _credentialProvider.SaveCredentials(credentials);

                OnConnectUserSignIn(result.getUser());

                response.onResponse();
            }

            @Override
            public void onError(Exception ex) {

                response.onError(ex);
            }
        });
    }

    public void CreatePin(String deviceId, Response<PinCreationResult> response)
    {
        connectService.CreatePin(deviceId, response);
    }

    public void GetPinStatus(PinCreationResult pin, Response<PinStatusResult> response)
    {
        connectService.GetPinStatus(pin, response);
    }

    public void ExchangePin(PinCreationResult pin, final Response<PinExchangeResult> response)
    {
        connectService.ExchangePin(pin, new ExchangePinResponse(_credentialProvider, response));
    }
}
