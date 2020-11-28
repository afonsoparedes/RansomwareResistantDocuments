package server;


import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.commons.io.FileUtils;
import proto.*;
import pt.ulisboa.tecnico.sdis.zk.ZKNaming;
import pt.ulisboa.tecnico.sdis.zk.ZKNamingException;
import pt.ulisboa.tecnico.sdis.zk.ZKRecord;
import server.database.Connector;
import server.domain.file.File;
import server.domain.file.FileRepository;
import server.domain.fileVersion.FileVersion;
import server.domain.fileVersion.FileVersionRepository;
import server.domain.user.User;
import server.domain.user.UserRepository;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLException;
import java.io.Console;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * Server that manages startup/shutdown of a {@code Greeter} server with TLS enabled.
 */
public class Server {

    private static final Logger logger = Logger.getLogger(Server.class.getName());
    private static final String SIRS_DIR = System.getProperty("user.dir");
    private final String partId;
    private final String id;
    private final String zooPort;
    private final String zooHost;
    private final String zooPath;
    private final String port;
    private final String host;
    private final String certChainFilePath;
    private final String privateKeyFilePath;
    private final String trustCertCollectionFilePath;
    private io.grpc.Server server;
    private ZKNaming zkNaming;
    private ManagedChannel channel;
    private ServerGrpc.ServerBlockingStub blockingStub;
    private KeyStore keyStore;
    private KeyStore trustCertStore;
    private KeyStore trustStore;

    public Server(String id,
                  String partId,
                  String zooPort,
                  String zooHost,
                  String port,
                  String host,
                  String certChainFilePath,
                  String privateKeyFilePath,
                  String trustCertCollectionFilePath) {
        this.id = id;
        this.partId = partId;
        this.zooPort = zooPort;
        this.zooHost = zooHost;
        this.zooPath = "/sirs/ransomware/servers/" + partId + "_" + id;
        this.host = host;
        this.port = port;
        this.certChainFilePath = certChainFilePath;
        this.privateKeyFilePath = privateKeyFilePath;
        this.trustCertCollectionFilePath = trustCertCollectionFilePath;


        Console console = System.console();
        String passwd = new String(console.readPassword("Enter private Key keyStore password: "));
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("PKCS12");
        } catch (KeyStoreException e) {
            e.printStackTrace();
        }
        try {
            assert ks != null;
            ks.load(new FileInputStream("src/assets/keyStores/privateKeyServerKeyStore.p12"), "5Xa)^WU_(rw$<}p%".toCharArray());
            this.keyStore = ks;
        } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            e.printStackTrace();
        }

        passwd = new String(console.readPassword("Enter trust cert keyStore password: "));
        KeyStore trustCertKeyStore = null;
        try {
            trustCertKeyStore = KeyStore.getInstance("PKCS12");
        } catch (KeyStoreException e) {
            e.printStackTrace();
        }
        try {
            assert trustCertKeyStore != null;
            trustCertKeyStore.load(new FileInputStream("src/assets/keyStores/trustCertsServerKeyStore.p12"), "w7my3n,~yvF-;Py3".toCharArray());
            this.trustCertStore = trustCertKeyStore;
        } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            e.printStackTrace();
        }

        passwd = new String(console.readPassword("Enter trustStore password: "));
        KeyStore trustStore = null;
        try {
            trustStore = KeyStore.getInstance("PKCS12");
        } catch (KeyStoreException e) {
            e.printStackTrace();
        }
        try {
            assert trustStore != null;
            trustStore.load(new FileInputStream("src/assets/keyStores/truststore.p12"), "w?#Sf@ZAL*tY7fVx".toCharArray());
            this.trustStore = trustStore;
        } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            e.printStackTrace();
        }


    }

    private static SslContext buildSslContext(String trustCertCollectionFilePath,
                                              String clientCertChainFilePath,
                                              String clientPrivateKeyFilePath) throws SSLException {
        SslContextBuilder builder = GrpcSslContexts.forClient();
        if (trustCertCollectionFilePath != null) {
            builder.trustManager(new java.io.File(trustCertCollectionFilePath));
        }
        if (clientCertChainFilePath != null && clientPrivateKeyFilePath != null) {
            builder.keyManager(new java.io.File(clientCertChainFilePath), new java.io.File(clientPrivateKeyFilePath));
        }
        return builder.build();
    }

    /**
     * Main launches the server from the command line.
     */
    public static void main(String[] args) throws IOException, InterruptedException {

        if (args.length != 9) {
            System.out.println(
                    "USAGE: ServerTls id partId zooHost zooPort host port certChainFilePath privateKeyFilePath " +
                            "trustCertCollectionFilePath\n  Note: You only need to supply trustCertCollectionFilePath if you want " +
                            "to enable Mutual TLS.");
            System.exit(0);
        }

        final Server server = new Server(
                args[0],
                args[1],
                args[2],
                args[3],
                args[4],
                args[5],
                args[6],
                args[7],
                args[8]);
        server.start();

        //server.greet("Server");
        server.blockUntilShutdown();


    }

    public static SecretKey readKey(String secretKeyPath) throws Exception {
        byte[] encoded = readFile(secretKeyPath);
        SecretKeySpec keySpec = new SecretKeySpec(encoded, "AES");
        return keySpec;
    }

    private static byte[] readFile(String path) throws FileNotFoundException, IOException {
        FileInputStream fis = new FileInputStream(path);
        byte[] content = new byte[fis.available()];
        fis.read(content);
        fis.close();
        return content;
    }

    public List<String> getZooPaths(String zooPath) {
        System.out.println(this.zooHost + ":" + this.zooPort);
        ZKNaming zkNaming = new ZKNaming(this.zooHost, this.zooPort);
        ArrayList<ZKRecord> recs = null;
        try {
            recs = new ArrayList<>(zkNaming.listRecords(zooPath));
        } catch (ZKNamingException e) {
            e.printStackTrace();
        }

        assert recs != null;
        return recs.stream().map(ZKRecord::getPath).collect(Collectors.toList());
    }

    /**
     * Construct client connecting to HelloWorld server at {@code host:port}.
     */
    public void changeChannel(String zooPath) throws SSLException {
        ZKRecord record = null;
        try {
            record = zkNaming.lookup(zooPath);
        } catch (ZKNamingException e) {
            e.printStackTrace();
        }
        assert record != null;
        this.channel = NettyChannelBuilder.forTarget(record.getURI())
                .overrideAuthority("foo.test.google.fr")  /* Only for using provided test certs. */
                .sslContext(buildSslContext(this.trustCertCollectionFilePath, this.certChainFilePath, this.privateKeyFilePath))
                .build();
        this.blockingStub = ServerGrpc.newBlockingStub(channel);
    }

    public void greet(String name) throws SSLException {
        changeChannel(getZooPaths("/sirs/ransomware/servers").get(0));
        System.out.println("Will try to greet " + name + " ...");
        HelloRequest request = HelloRequest.newBuilder().setName(name).build();
        HelloReply response;
        try {
            response = this.blockingStub.sayHello(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return;
        }
        System.out.println("Greeting: " + response.getMessage());
    }

    private SslContextBuilder getSslContextBuilder() {
        SslContextBuilder sslClientContextBuilder = SslContextBuilder.forServer(new java.io.File(certChainFilePath),
                new java.io.File(privateKeyFilePath));
        if (trustCertCollectionFilePath != null) {
            sslClientContextBuilder.trustManager(new java.io.File(trustCertCollectionFilePath));
            sslClientContextBuilder.clientAuth(ClientAuth.REQUIRE);
        }
        return GrpcSslContexts.configure(sslClientContextBuilder);
    }

    private void start() throws IOException {
        server = NettyServerBuilder.forAddress(new InetSocketAddress(host, Integer.parseInt(port)))
                .addService(new ServerImp(this.keyStore,this.trustCertStore,this.trustStore))
                .sslContext(getSslContextBuilder().build())
                .build()
                .start();
        //Adds server to naming server
        addToZooKeeper();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may have been reset by its JVM shutdown hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            Server.this.stop();
            //removes server from naming server
            removeFromZooKeeper();
            System.err.println("*** server shut down");
        }));
    }

    private void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    private void addToZooKeeper() {

        this.zkNaming = new ZKNaming(zooHost, zooPort);
        try {
            zkNaming.rebind(this.zooPath, host, this.port);
        } catch (ZKNamingException e) {
            e.printStackTrace();
        }
    }

    private void removeFromZooKeeper() {
        if (zkNaming != null) {
            // remove
            try {
                zkNaming.unbind(this.zooPath, host, this.port);
            } catch (ZKNamingException e) {
                e.printStackTrace();
            }
        }
    }

    private static PrivateKey readPrivateKey(String privateKeyPath) {
        String key = null;
        try {
            key = Files.readString(Paths.get(privateKeyPath), Charset.defaultCharset());
        } catch (IOException e) {
            e.printStackTrace();
        }

        assert key != null;
        String privateKeyPEM = key
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll(System.lineSeparator(), "")
                .replace("-----END PRIVATE KEY-----", "");

        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);

        KeyFactory keyFactory = null;
        try {
            keyFactory = KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        try {
            assert keyFactory != null;
            return keyFactory.generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    static class ServerImp extends ServerGrpc.ServerImplBase {
        private final static int ITERATIONS = 10000;
        private KeyStore keyStore;
        private KeyStore trustCertStore;
        private KeyStore trustStore;
        Connector c;
        UserRepository userRepository;
        FileRepository fileRepository;
        FileVersionRepository fileVersionRepository;

        public ServerImp(KeyStore keyStore,
                KeyStore trustCertStore,
                KeyStore trustStore) {
            try {
                c = new Connector();
                userRepository = new UserRepository(c.getConnection());
                fileRepository = new FileRepository(c.getConnection());
                fileVersionRepository = new FileVersionRepository(c.getConnection());
                this.keyStore = keyStore;
                this.trustCertStore = trustCertStore;
                this.trustStore = trustStore;
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }


        @Override
        public void salt(SaltRequest request, StreamObserver<SaltReply> responseObserver) {
            User user = userRepository.getUserByUsername(request.getUsername());
            SaltReply reply = SaltReply.newBuilder().setSalt(ByteString.copyFrom(user.getSalt())).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void register(RegisterRequest req, StreamObserver<RegisterReply> responseObserver) {
            RegisterReply reply;
            if (req.getUsername().length() > 15 || req.getUsername().length() == 0)
                reply = RegisterReply.newBuilder().setOk("Username too long").build();
            else if (usernameExists(req.getUsername()))
                reply = RegisterReply.newBuilder().setOk("Duplicate user with username " + req.getUsername()).build();
            else {
                registerUser(req.getUsername(), req.getPassword().toByteArray(), req.getSalt().toByteArray(),req.getPublicKey().toByteArray());
                reply = RegisterReply.newBuilder().setOk("User " + req.getUsername() + " registered successfully").build();
            }
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void login(LoginRequest req, StreamObserver<LoginReply> responseObserver) {
            LoginReply reply;
            if (req.getUsername().length() > 15 || req.getUsername().length() == 0 || !usernameExists(req.getUsername()))
                reply = LoginReply.newBuilder().setOkUsername(false).setOkPassword(false).build();
            else {
                if (isCorrectPassword(req.getUsername(), req.getPassword().toByteArray()))
                    reply = LoginReply.newBuilder().setOkUsername(true).setOkPassword(true).build();
                else
                    reply = LoginReply.newBuilder().setOkUsername(true).setOkPassword(false).build();
            }
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void getPublicKeysByFile(GetPublicKeysByFileRequest request, StreamObserver<GetPublicKeysByFileReply> responseObserver) {
            GetPublicKeysByFileReply reply = GetPublicKeysByFileReply
                    .newBuilder()
                    .addAllKeys(
                            fileRepository.getPublicKeysByFile(request.getFileUid())
                                    .stream()
                                    .map(ByteString::copyFrom)
                                    .collect(Collectors.toList())
                    )
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void getPublicKeysByUsernames(GetPublicKeysByUsernamesRequest request, StreamObserver<GetPublicKeysByUsernamesReply> responseObserver) {
            GetPublicKeysByUsernamesReply reply = GetPublicKeysByUsernamesReply
                    .newBuilder()
                    .addAllKeys(
                            userRepository.getPublicKeysByUsernames(request.getUsernamesList())
                                    .stream()
                                    .map(ByteString::copyFrom)
                                    .collect(Collectors.toList())
                    )
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void getFileOwnerPublicKey(GetFileOwnerPublicKeyRequest request, StreamObserver<GetFileOwnerPublicKeyReply> responseObserver) {
            GetFileOwnerPublicKeyReply reply = GetFileOwnerPublicKeyReply
                    .newBuilder()
                    .setPublicKey(ByteString.copyFrom(fileRepository.getFileOwnerPublicKey(request.getUid())))
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void push(PushRequest req, StreamObserver<PushReply> responseObserver) {

            ByteString bs = req.getFile();
            System.out.println("Received file from client " + req.getUsername());
            PushReply reply = null;
            if (isCorrectPassword(req.getUsername(), req.getPassword().toByteArray())) {
                String versionId = UUID.randomUUID().toString();
                byte[] bytes = bs.toByteArray();
                try {
                    FileUtils.writeByteArrayToFile(new java.io.File(SIRS_DIR + "/src/assets/serverFiles/" + versionId), bytes);
                    reply = PushReply.newBuilder().setOk(true).build();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //REGISTER FILE
                if (!this.fileRepository.fileExists(req.getUid())) {
                    registerFile(req.getUid(), req.getFileName(), req.getUsername(), req.getPartId(), req.getAESEncrypted().toByteArray(),req.getIv().toByteArray());
                }
                registerFileVersion(versionId, req.getUid(), req.getUsername(),req.getDigitalSignature().toByteArray());

            } else reply = PushReply.newBuilder().setOk(false).build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void pullAll(PullAllRequest req, StreamObserver<PullReply> responseObserver) {
            if (!isCorrectPassword(req.getUsername(), req.getPassword().toByteArray())) {
                responseObserver.onNext(PullReply.newBuilder().setOk(false).build());
                responseObserver.onCompleted();
                return;
            }
            PullReply.Builder reply = PullReply.newBuilder().setOk(true);
            List<File> readableFiles = this.fileRepository.getUserReadableFiles(req.getUsername());
            for (File file : readableFiles) {
                System.out.println("Sending file " + file.getName() + " " + file.getUid() + " to client " + req.getUsername());
                FileVersion mostRecentVersion = fileVersionRepository.getMostRecentVersion(file.getUid());
                byte[] file_bytes = new byte[0];
                try {
                    file_bytes = Files.readAllBytes(
                            Paths.get(SIRS_DIR + "/src/assets/serverFiles/" + mostRecentVersion.getVersionUid()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                reply.addUids(mostRecentVersion.getUid());
                reply.addFilenames(file.getName());
                reply.addOwners(file.getOwner());
                reply.addIvs(ByteString.copyFrom(file.getIv()));
                reply.addPartIds(file.getPartition());
                reply.addFiles(ByteString.copyFrom(
                        file_bytes));
                reply.addPublicKeys(ByteString.copyFrom(fileRepository.getFileOwnerPublicKey(mostRecentVersion.getUid())));
                reply.addDigitalSignatures(ByteString.copyFrom(mostRecentVersion.getDigitalSignature()));
                reply.addAESEncrypted(ByteString.copyFrom(getAESEncrypted(req.getUsername(),file.getUid())));
            }
            responseObserver.onNext(reply.build());
            responseObserver.onCompleted();
        }

        @Override
        public void pullSelected(PullSelectedRequest req, StreamObserver<PullReply> responseObserver) {
            if (!isCorrectPassword(req.getUsername(), req.getPassword().toByteArray())) {
                responseObserver.onNext(PullReply.newBuilder().setOk(false).build());
                responseObserver.onCompleted();
                return;
            }
            PullReply.Builder reply = PullReply.newBuilder().setOk(true);
            List<File> readableFiles = this.fileRepository.getUserReadableFiles(req.getUsername());
            for (int i = 0; i < req.getFilenamesCount(); i++) {
                for (File file : readableFiles) {
                    if (req.getFilenames(i).equals(file.getName())) {
                        System.out.println("Sending file " + file.getName() + " to client " + req.getUsername());
                        FileVersion mostRecentVersion = fileVersionRepository.getMostRecentVersion(file.getUid());
                        byte[] file_bytes = new byte[0];
                        try {
                            file_bytes = Files.readAllBytes(
                                    Paths.get(SIRS_DIR + "/src/assets/serverFiles/" + mostRecentVersion.getVersionUid()));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        reply.addUids(file.getUid());
                        reply.addFilenames(file.getName());
                        reply.addOwners(file.getOwner());
                        reply.addIvs(ByteString.copyFrom(file.getIv()));
                        reply.addPartIds(file.getPartition());
                        reply.addFiles(ByteString.copyFrom(
                                file_bytes));
                        reply.addPublicKeys(ByteString.copyFrom(fileRepository.getFileOwnerPublicKey(file.getUid())));
                        reply.addDigitalSignatures(ByteString.copyFrom(mostRecentVersion.getDigitalSignature()));
                        reply.addAESEncrypted(ByteString.copyFrom(getAESEncrypted(req.getUsername(),mostRecentVersion.getUid())));
                        break;
                    }
                }
            }
            responseObserver.onNext(reply.build());
            responseObserver.onCompleted();
        }

        @Override
        public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            HelloReply reply = HelloReply.newBuilder().setMessage("Hello" + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void givePermission(GivePermissionRequest req, StreamObserver<GivePermissionReply> responseObserver) {
            GivePermissionReply reply = null;
            if (req.getMode().matches("read|write")) {
                if (allUsernamesExist(req.getOthersNamesList())) {
                    if (filenameExists(req.getUid())) {
                        giveUsersPermission(req.getOthersNamesList(),
                                req.getUid(),
                                req.getMode(),
                                req.getOtherAESEncryptedList()
                                        .stream()
                                        .map(ByteString::toByteArray)
                                        .collect(Collectors.toList())
                        );
                        reply = GivePermissionReply.newBuilder().setOkOthers(true).setOkUid(true).setOkMode(true).build();
                    }
                } else
                    reply = GivePermissionReply.newBuilder().setOkOthers(false).setOkUid(false).setOkMode(true).build();
            } else
                reply = GivePermissionReply.newBuilder().setOkOthers(false).setOkUid(false).setOkMode(false).build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
        @Override
        public void getAESEncrypted(GetAESEncryptedRequest req, StreamObserver<GetAESEncryptedReply> responseObserver){
            GetAESEncryptedReply reply;

            if (isOwner(req.getUsername(), req.getUid())){

                byte[] aes = getAESEncrypted(req.getUsername(),req.getUid());

                List<byte[]> pk = userRepository.getPublicKeysByUsernames(req.getOthersNamesList());
                reply = GetAESEncryptedReply.newBuilder().setIsOwner(true).setAESEncrypted(ByteString.copyFrom(aes))
                        .addAllOthersPublicKeys(pk.stream().map(ByteString::copyFrom).collect(Collectors.toList())).build();


            } else reply = GetAESEncryptedReply.newBuilder().setIsOwner(false).build();


            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        private boolean isCorrectPassword(String username, byte[] password) {

            byte[] userSecret = userRepository.getUserPassword(username);
            return (Arrays.equals(password, userSecret));

        }

        private void registerUser(String name, byte[] password, byte[] salt, byte[] publicKeyBytes) {
            User user = new User(name, password, salt, ITERATIONS, publicKeyBytes);
            user.saveInDatabase(this.c);
        }

        private void registerFile(String uid, String filename, String owner, String partId, byte[] AESEncrypted,byte[] iv) {
            server.domain.file.File file = new server.domain.file.File(uid, owner, filename, partId, AESEncrypted, iv);
            file.saveInDatabase(this.c);
        }

        private void registerFileVersion(String versionId, String fileId, String creator, byte[] digitalSignature) {
            FileVersion fileVersion = new FileVersion(versionId, fileId, creator, new Date(System.currentTimeMillis()),digitalSignature);
            fileVersion.saveInDatabase(this.c);
        }

        private boolean usernameExists(String name) {
            User user = userRepository.getUserByUsername(name);
            return user.getUsername() != null && user.getPassHash() != null && user.getSalt() != null;
        }
        private boolean allUsernamesExist(List<String> names) {
            boolean bool=true;
            for(String name : names) {
                User user = userRepository.getUserByUsername(name);
                if (user.getUsername() == null || user.getPassHash() == null || user.getSalt() == null || user.getPublicKey() == null) {
                    bool = false;
                }
            }
            return bool;

        }

        private boolean filenameExists(String uid) {
            File file = fileRepository.getFileByUID(uid);
            return file.getUid() != null && file.getName() != null && file.getPartition() != null && file.getOwner() != null;
        }

        private void giveUsersPermission(List<String> usernames, String uid, String mode, List<byte[]> AESEncrypted ) {
            for (int i=0; i < usernames.size();i++) {
                userRepository.setUserPermissionFile(usernames.get(i), uid, mode, AESEncrypted.get(i));
            }
        }
        private List<String> getUsersWithPermission(String uid, String mode){
            return userRepository.getUsersWithPermissions(uid,mode);
        }

        private byte[] getPublicKey(String username){
            return userRepository.getPublicKey(username);
        }

        private byte[] getAESEncrypted(String username, String uid){
            return fileRepository.getAESEncrypted(username,uid);
        }
        private boolean isOwner(String username, String uid){
            return userRepository.isOwner(username,uid);
        }


        private SecretKey retrieveStoredKey() {
            SecretKey secretKey = null;
            try {
                //TODO provide a password
                secretKey = (SecretKey) this.keyStore.getKey("db-encryption-secret", "".toCharArray());
                System.out.println(keyStore.containsAlias("db-encryption-secret"));
            } catch (NoSuchAlgorithmException | KeyStoreException | UnrecoverableKeyException e) {
                e.printStackTrace();
            }
            return secretKey;
        }
    }
}