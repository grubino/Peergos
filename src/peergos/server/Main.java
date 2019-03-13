package peergos.server;

import peergos.server.storage.admin.*;
import peergos.shared.*;
import peergos.server.corenode.*;
import peergos.server.fuse.*;
import peergos.server.mutable.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.password.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    public static final String PEERGOS_PATH = "PEERGOS_PATH";
    public static final Path DEFAULT_PEERGOS_DIR_PATH =
            Paths.get(System.getProperty("user.home"), ".peergos");

    static {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    }

    public static final Command ENSURE_IPFS_INSTALLED = new Command("install-ipfs",
            "Download/update IPFS binary. Does nothing if current IPFS binary is up-to-date.",
            args -> {
                Path ipfsExePath = IpfsWrapper.getIpfsExePath(args);
                File dir = ipfsExePath.getParent().toFile();
                if (!  dir.isDirectory() && ! dir.mkdirs())
                    throw new IllegalStateException("Specified install directory "+ dir +" doesn't exist and can't be created");

                IpfsInstaller.ensureInstalled(ipfsExePath);
            },
            Arrays.asList(
                    new Command.Arg("ipfs-exe-path", "Desired path to IPFS executable. Defaults to $PEERGOS_PATH/ipfs", false)
            )
    );
    public static final Command IPFS = new Command("ipfs",
            "Start IPFS daemon and ensure configuration, optionally manage runtime.",
            Main::startIpfs,
            Arrays.asList(
                    new Command.Arg("IPFS_PATH", "Path to IPFS directory. Defaults to $PEERGOS_PATH/.ipfs, or ~/.peergos/.ipfs", false),
                    new Command.Arg("ipfs-exe-path", "Path to IPFS executable. Defaults to $PEERGOS_PATH/ipfs", false),
                    new Command.Arg("ipfs-config-api-port", "IPFS API port", false, "5001"),
                    new Command.Arg("ipfs-config-gateway-port", "IPFS Gateway port", false, "8080"),
                    new Command.Arg("ipfs-config-swarm-port", "IPFS Swarm port", false, "4001"),
                    new Command.Arg("ipfs-config-bootstrap-node-list", "Comma separated list of IPFS bootstrap nodes. Uses existing bootstrap nodes by default.", false),
                    new Command.Arg("ipfs-manage-runtime", "Will manage the IPFS daemon runtime when set (restart on exit)", false, "true")
            )
    );

    public static final Command PEERGOS = new Command("peergos",
            "The user facing Peergos server",
            Main::startPeergos,
            Stream.of(
                    new Command.Arg("port", "service port", false, "8000"),
                    new Command.Arg("pki-node-id", "Ipfs node id of the pki node", true),
                    new Command.Arg("domain", "Domain name to bind to,", false, "localhost"),
                    new Command.Arg("useIPFS", "Use IPFS for storage or a local disk store", false, "true"),
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers datastore", true, "mutable.sql"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests datastore", true, "social.sql"),
                    new Command.Arg("webroot", "the path to the directory to serve as the web root", false),
                    new Command.Arg("default-quota", "default maximum storage per user", false, Long.toString(1024L * 1024 * 1024))
            ).collect(Collectors.toList())
    );

    public static final Command BOOTSTRAP = new Command("bootstrap",
            "Bootstrap a new peergos network\n" +
                    "This means creating a pki keypair and publishing the public key",
            args -> {
                try {
                    Crypto crypto = Crypto.initJava();
                    // setup peergos user and pki keys
                    String testpassword = args.getArg("peergos.password");
                    String pkiUsername = "peergos";
                    UserWithRoot peergos = UserUtil.generateUser(pkiUsername, testpassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefault()).get();

                    boolean useIPFS = args.getBoolean("useIPFS");
                    String ipfsApiAddress = args.getArg("ipfs-api-address", "/ip4/127.0.0.1/tcp/5001");
                    ContentAddressedStorage dht = useIPFS ?
                            new IpfsDHT(new MultiAddress(ipfsApiAddress)) :
                            new FileContentAddressedStorage(blockstorePath(args));

                    SigningKeyPair peergosIdentityKeys = peergos.getUser();
                    PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);

                    String pkiPassword = args.getArg("pki.keygen.password");
                    SigningKeyPair pkiKeys = UserUtil.generateUser(pkiUsername, pkiPassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefault()).get().getUser();
                    IpfsTransaction.call(peergosPublicHash,
                            tid -> dht.putSigningKey(peergosIdentityKeys.secretSigningKey.signatureOnly(
                                    pkiKeys.publicSigningKey.serialize()),
                                    peergosPublicHash,
                                    pkiKeys.publicSigningKey, tid), dht).get();

                    String pkiKeyfilePassword = args.getArg("pki.keyfile.password");
                    Cborable cipherTextCbor = PasswordProtected.encryptWithPassword(pkiKeys.secretSigningKey.toCbor().toByteArray(),
                            pkiKeyfilePassword,
                            crypto.hasher,
                            crypto.symmetricProvider,
                            crypto.random);
                    Files.write(args.fromPeergosDir("pki.secret.key.path"), cipherTextCbor.serialize());
                    Files.write(args.fromPeergosDir("pki.public.key.path"), pkiKeys.publicSigningKey.toCbor().toByteArray());
                    args.setIfAbsent("peergos.identity.hash", peergosPublicHash.toString());

                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            },
            Arrays.asList(
                    new Command.Arg("useIPFS", "Use IPFS for storage or ephemeral RAM store", false, "true"),
                    new Command.Arg("peergos.password",
                            "The password for the peergos user required to bootstrap the network", true),
                    new Command.Arg("pki.keygen.password", "The password used to generate the pki key pair", true),
                    new Command.Arg("pki.keyfile.password", "The password used to protect the pki private key on disk", true),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true),
                    new Command.Arg("pki.secret.key.path", "The path to the pki secret key file", true)
            )
    );

    public static final Command POSTSTRAP = new Command("poststrap",
            "The final step of bootstrapping a new peergos network, which must be run once after network bootstrap\n" +
                    "This means signing up the peergos user, and adding the pki public key to the peergos user",
            args -> {
                try {
                    Crypto crypto = Crypto.initJava();
                    // recreate peergos user and pki keys
                    String password = args.getArg("peergos.password");
                    String pkiUsername = "peergos";
                    UserWithRoot peergos = UserUtil.generateUser(pkiUsername, password, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefault()).get();

                    SigningKeyPair peergosIdentityKeys = peergos.getUser();
                    PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);
                    PublicSigningKey pkiPublic =
                            PublicSigningKey.fromByteArray(
                                    Files.readAllBytes(args.fromPeergosDir("pki.public.key.path")));
                    PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiPublic);
                    int webPort = args.getInt("port");
                    NetworkAccess network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();

                    // sign up peergos user
                    UserContext context = UserContext.ensureSignedUp(pkiUsername, password, network, crypto).get();
                    Optional<PublicKeyHash> existingPkiKey = context.getNamedKey("pki").get();
                    if (!existingPkiKey.isPresent() || existingPkiKey.get().equals(pkiPublicHash)) {
                        context.addNamedOwnedKeyAndCommit("pki", pkiPublicHash).get();
                        // write pki public key to ipfs
                        IpfsTransaction.call(peergosPublicHash,
                                tid -> network.dhtClient.putSigningKey(peergosIdentityKeys.secretSigningKey
                                .signatureOnly(pkiPublic.serialize()), peergosPublicHash, pkiPublic, tid),
                                network.dhtClient).get();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            },
            Arrays.asList(
                    new Command.Arg("peergos.password",
                            "The password for the peergos user required to bootstrap the network", true),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true)
            )
    );

    public static final Command PKI = new Command("pki",
            "Bootstrap and start the Peergos PKI Server",
            args -> {
                try {
                    int peergosPort = args.getInt("port");
                    int ipfsApiPort = args.getInt("ipfs-config-api-port");
                    args.setIfAbsent("proxy-target", getLocalMultiAddress(peergosPort).toString());

                    IpfsWrapper ipfs = null;
                    boolean useIPFS = args.getBoolean("useIPFS");
                    if (useIPFS) {
                        ENSURE_IPFS_INSTALLED.main(args);
                        ipfs = startIpfs(args);
                    }

                    args.setArg("ipfs-api-address", getLocalMultiAddress(ipfsApiPort).toString());
                    BOOTSTRAP.main(args);

                    Multihash pkiIpfsNodeId = useIPFS ?
                            new IpfsDHT(getLocalMultiAddress(ipfsApiPort)).id().get() :
                            new FileContentAddressedStorage(blockstorePath(args)).id().get();

                    if (ipfs != null)
                        ipfs.stop();
                    args.setIfAbsent("pki-node-id", pkiIpfsNodeId.toBase58());
                    PEERGOS.main(args);
                    POSTSTRAP.main(args);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            },
            Arrays.asList(
                    new Command.Arg("domain", "The hostname to listen on", true, "localhost"),
                    new Command.Arg("port", "The port for the local non tls server to listen on", true, "8000"),
                    new Command.Arg("useIPFS", "Whether to use IPFS or a local datastore", true, "false"),
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers (or :memory: or ram based)", true, ":memory:"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests (or :memory: or ram based)", true, ":memory:"),
                    new Command.Arg("ipfs-config-api-port", "ipfs api port", true, "5001"),
                    new Command.Arg("ipfs-config-gateway-port", "ipfs gateway port", true, "8080"),
                    new Command.Arg("pki.secret.key.path", "The path to the pki secret key file", true, "test.pki.secret.key"),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true, "test.pki.public.key"),
                    // Secret parameters
                    new Command.Arg("peergos.password", "The password for the 'peergos' user", true),
                    new Command.Arg("pki.keygen.password", "The password to generate the pki key from", true),
                    new Command.Arg("pki.keyfile.password", "The password protecting the pki keyfile", true)
            )
    );

    public static final Command FUSE = new Command("fuse",
            "Mount a Peergos user's filesystem natively",
            Main::startFuse,
            Stream.of(
                    new Command.Arg("username", "Peergos username", true),
                    new Command.Arg("password", "Peergos password", true),
                    new Command.Arg("webport", "Peergos service address port", false, "8000"),
                    new Command.Arg("mountPoint", "The directory to mount the Peergos filesystem in", true, "peergos")
            ).collect(Collectors.toList())
    );

    public static void startPeergos(Args a) {
        try {
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
            int webPort = a.getInt("port");
            a.setIfAbsent("proxy-target", getLocalMultiAddress(webPort).toString());

            boolean useIPFS = a.getBoolean("useIPFS");
            if (useIPFS) {
                ENSURE_IPFS_INSTALLED.main(a);
                IPFS.main(a);
            }

            Multihash pkiServerNodeId = Cid.decode(a.getArg("pki-node-id"));
            URL ipfsApiAddress = AddressUtil.getLocalAddress(a.getInt("ipfs-config-api-port"));
            URL ipfsGatewayAddress = AddressUtil.getLocalAddress(a.getInt("ipfs-config-gateway-port"));
            String domain = a.getArg("domain");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

            int dhtCacheEntries = 1000;
            int maxValueSizeToCache = 50 * 1024;
            JavaPoster ipfsApi = new JavaPoster(ipfsApiAddress);
            JavaPoster ipfsGateway = new JavaPoster(ipfsGatewayAddress);

            ContentAddressedStorage localDht = useIPFS ?
                    new CachingStorage(new ContentAddressedStorage.HTTP(ipfsApi, false), dhtCacheEntries, maxValueSizeToCache) :
                    new FileContentAddressedStorage(blockstorePath(a));

            String hostname = a.getArg("domain");
            int maxUserCount = a.getInt("max-user-count", CoreNode.MAX_USERNAME_COUNT);
            Multihash nodeId = localDht.id().get();

            String mutablePointersSqlFile = a.getArg("mutable-pointers-file");
            String path = mutablePointersSqlFile.equals(":memory:") ?
                    mutablePointersSqlFile :
                    a.fromPeergosDir("mutable-pointers-file").toString();
            MutablePointers sqlMutable = UserRepository.buildSqlLite(path, localDht, maxUserCount);
            MutablePointersProxy proxingMutable = new HttpMutablePointers(ipfsGateway, pkiServerNodeId);

            PublicKeyHash peergosId = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));
            // build a mirroring proxying corenode, unless we are the pki node
            boolean isPkiNode = nodeId.equals(pkiServerNodeId);
            CoreNode core = isPkiNode ?
                    buildPkiCorenode(sqlMutable, localDht, a) :
                    new MirrorCoreNode(new HTTPCoreNode(ipfsGateway, pkiServerNodeId), localDht, proxingMutable, peergosId);

            long defaultQuota = a.getLong("default-quota");
            Logging.LOG().info("Using default user space quota of " + defaultQuota);
            Path quotaFilePath = a.fromPeergosDir("quotas_file","quotas.txt");
            Path statePath = a.fromPeergosDir("state_path","usage-state.cbor");

            UserQuotas userQuotas = new UserQuotas(quotaFilePath, defaultQuota);
            SpaceCheckingKeyFilter spaceChecker = new SpaceCheckingKeyFilter(core, sqlMutable, localDht, userQuotas::quota, statePath);
            CorenodeEventPropagator corePropagator = new CorenodeEventPropagator(core);
            corePropagator.addListener(spaceChecker::accept);
            MutableEventPropagator localMutable = new MutableEventPropagator(sqlMutable);
            localMutable.addListener(spaceChecker::accept);

            ContentAddressedStorage filteringDht = new WriteFilter(localDht, spaceChecker::allowWrite);
            ContentAddressedStorageProxy proxingDht = new ContentAddressedStorageProxy.HTTP(ipfsGateway);
            ContentAddressedStorage p2pDht = new ContentAddressedStorage.Proxying(filteringDht, proxingDht, nodeId, core);

            Path blacklistPath = a.fromPeergosDir("blacklist_file", "blacklist.txt");
            PublicKeyBlackList blacklist = new UserBasedBlacklist(blacklistPath, core, localMutable, p2pDht);
            MutablePointers blockingMutablePointers = new BlockingMutablePointers(new PinningMutablePointers(localMutable, p2pDht), blacklist);
            MutablePointers p2mMutable = new ProxyingMutablePointers(nodeId, core, blockingMutablePointers, proxingMutable);

            SocialNetworkProxy httpSocial = new HttpSocialNetwork(ipfsGateway, ipfsGateway);
            String socialNodeFile = a.getArg("social-sql-file");
            String socialPath = socialNodeFile.equals(":memory:") ?
                    socialNodeFile :
                    a.fromPeergosDir("social-sql-file").toString();
            SocialNetwork local = UserRepository.buildSqlLite(socialPath, p2pDht, maxUserCount);
            SocialNetwork p2pSocial = new ProxyingSocialNetwork(nodeId, core, local, httpSocial);

            Path userPath = a.fromPeergosDir("whitelist_file", "user_whitelist.txt");
            int delayMs = a.getInt("whitelist_sleep_period", 1000 * 60 * 10);

            new UserFilePinner(userPath, core, p2mMutable, p2pDht, delayMs).start();

            Admin storageAdmin = new Admin();
            UserService peergos = new UserService(p2pDht, corePropagator, p2pSocial, p2mMutable, storageAdmin);
            InetSocketAddress localAddress = new InetSocketAddress("localhost", userAPIAddress.getPort());
            Optional<Path> webroot = a.hasArg("webroot") ?
                    Optional.of(Paths.get(a.getArg("webroot"))) :
                    Optional.empty();
            boolean useWebAssetCache = a.getBoolean("webcache", true);
            Optional<String> tlsHostname = hostname.equals("localhost") ? Optional.empty() : Optional.of(hostname);
            Optional<UserService.TlsProperties> tlsProps =
                    tlsHostname.map(host -> new UserService.TlsProperties(host, a.getArg("tls.keyfile.password")));
            peergos.initAndStart(localAddress, tlsProps, webroot, useWebAssetCache);
            if (! isPkiNode)
                ((MirrorCoreNode) core).start();
            spaceChecker.calculateUsage();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void startFuse(Args a) {
        String username = a.getArg("username");
        String password = a.getArg("password");

        int webPort = a.getInt("webport");
        try {
            Files.createTempDirectory("peergos").toString();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        String mountPath = a.getArg("mountPoint");
        Path path = Paths.get(mountPath);

        path.toFile().mkdirs();

        System.out.println("\n\nPeergos mounted at " + path + "\n\n");
        try {
            NetworkAccess network = NetworkAccess.buildJava(webPort).get();
            Crypto crypto = Crypto.initJava();
            UserContext userContext = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
            PeergosFS peergosFS = new PeergosFS(userContext);
            FuseProcess fuseProcess = new FuseProcess(peergosFS, path);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> fuseProcess.close()));

            fuseProcess.start();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    public static IpfsWrapper startIpfs(Args a) {
        // test if ipfs is already running
        int ipfsApiPort = IpfsWrapper.getApiPort(a);
        if (IpfsWrapper.isHttpApiListening(ipfsApiPort)) {
            throw new IllegalStateException("IPFS is already running on api port " + ipfsApiPort);
        }

        IpfsWrapper ipfs = IpfsWrapper.build(a);

        if (a.getBoolean("ipfs-manage-runtime", true))
            IpfsWrapper.launchAndManage(ipfs);
        else {
            IpfsWrapper.launchOnce(ipfs);
        }
        // wait for daemon to finish starting
        ipfs.waitForDaemon(10);
        // set up p2p proxy receiver
        ipfs.startP2pProxy(new MultiAddress(a.getArg("proxy-target")));
        return ipfs;
    }

    public static void startCoreNode(Args a) {
        String mutablePointersSqlFile = a.getArg("mutable-pointers-file");
        String path = mutablePointersSqlFile.equals(":memory:") ?
                mutablePointersSqlFile :
                a.fromPeergosDir("mutable.sql").toString();
        int corenodePort = a.getInt("corenode-port");
        int maxUserCount = a.getInt("max-user-count", CoreNode.MAX_USERNAME_COUNT);
        System.out.println("Using mutable-pointers path " + path);
        boolean useIPFS = a.getBoolean("useIPFS");

        int dhtCacheEntries = 1000;
        int maxValueSizeToCache = 2 * 1024 * 1024;
        ContentAddressedStorage dht = useIPFS ?
                new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) :
                new FileContentAddressedStorage(blockstorePath(a));
        try {
            Crypto crypto = Crypto.initJava();
            MutablePointers mutable = UserRepository.buildSqlLite(path
                    , dht, maxUserCount);
            PublicKeyHash peergosIdentity = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));

            String pkiSecretKeyfilePassword = a.getArg("pki.keyfile.password");

            PublicSigningKey pkiPublic =
                    PublicSigningKey.fromByteArray(
                            Files.readAllBytes(Paths.get(a.getArg("pki.public.key.path"))));
            SecretSigningKey pkiSecretKey = SecretSigningKey.fromCbor(CborObject.fromByteArray(
                    PasswordProtected.decryptWithPassword(
                            CborObject.fromByteArray(Files.readAllBytes(Paths.get(a.getArg("pki.secret.key.path")))),
                            pkiSecretKeyfilePassword,
                            crypto.hasher,
                            crypto.symmetricProvider,
                            crypto.random
                    )));
            SigningKeyPair pkiKeys = new SigningKeyPair(pkiPublic, pkiSecretKey);
            PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiKeys.publicSigningKey);

            MaybeMultihash currentPkiRoot = mutable.getPointerTarget(peergosIdentity, pkiPublicHash, dht).get();

            IpfsCoreNode core = new IpfsCoreNode(pkiKeys, currentPkiRoot, dht, mutable, peergosIdentity);
            HttpCoreNodeServer.createAndStart(corenodePort, core, mutable, a);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CoreNode buildPkiCorenode(MutablePointers mutable, ContentAddressedStorage dht, Args a) {
        try {
            Crypto crypto = Crypto.initJava();
            PublicKeyHash peergosIdentity = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));

            String pkiSecretKeyfilePassword = a.getArg("pki.keyfile.password");

            PublicSigningKey pkiPublic =
                    PublicSigningKey.fromByteArray(
                            Files.readAllBytes(a.fromPeergosDir("pki.public.key.path")));
            SecretSigningKey pkiSecretKey = SecretSigningKey.fromCbor(CborObject.fromByteArray(
                    PasswordProtected.decryptWithPassword(
                            CborObject.fromByteArray(Files.readAllBytes(a.fromPeergosDir("pki.secret.key.path"))),
                            pkiSecretKeyfilePassword,
                            crypto.hasher,
                            crypto.symmetricProvider,
                            crypto.random
                    )));
            SigningKeyPair pkiKeys = new SigningKeyPair(pkiPublic, pkiSecretKey);
            PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiKeys.publicSigningKey);

            MaybeMultihash currentPkiRoot = mutable.getPointerTarget(peergosIdentity, pkiPublicHash, dht).get();

            return new IpfsCoreNode(pkiKeys, currentPkiRoot, dht, mutable, peergosIdentity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final Command MAIN = new Command("Main",
            "Run a Peergos command",
            args -> {
                Optional<String> top = args.head();
                if (!top.isPresent()) {
                    System.out.println("Run with -help to show options");
                    return;
                }
                args.setIfAbsent("domain", "localhost");
                if (args.getBoolean("useIPFS", true))
                    startIpfs(args);
                startCoreNode(args);
                startPeergos(args);
            },
            Collections.emptyList(),
            Arrays.asList(
                    PKI,
                    PEERGOS,
                    FUSE
            )
    );

    /**
     * Create path to local blockstore directory from Args.
     *
     * @param args
     * @return
     */
    private static Path blockstorePath(Args args) {
        return args.fromPeergosDir("blockstore_dir", "blockstore");
    }

    public static MultiAddress getLocalMultiAddress(int port) {
        return new MultiAddress("/ip4/127.0.0.1/tcp/" + port);
    }

    public static MultiAddress getLocalBootstrapAddress(int port, Multihash nodeId) {
        return new MultiAddress("/ip4/127.0.0.1/tcp/" + port + "/ipfs/"+ nodeId);
    }

    public static void main(String[] args) {
        MAIN.main(Args.parse(args));
    }
}
