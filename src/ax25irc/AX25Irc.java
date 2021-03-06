package ax25irc;

import ax25irc.ircd.server.MessageListener;
import ax25irc.ircd.server.Channel;
import ax25irc.ircd.server.Client;
import ax25irc.ircd.server.ClientConnectionListener;
import ax25irc.ircd.server.IRCServer;
import ax25irc.ircd.server.ServMessage;
import java.util.Iterator;
import java.util.Map;

public class AX25Irc extends Thread implements AX25PacketListener, MessageListener, ClientConnectionListener {

    static String listen_ip = "0.0.0.0";
    static String listen_port = "6667";
    long maxIdleTime = 600000;

    String extra;

    public static enum RadioMode {
        KISS,
        RTL_FM,
        STDIN,
        SOUND
    };

    public static enum MessageMode {
        APRS,
        AX25
    };

    MessageMode messageMode;
    RadioMode radioMode;
    IRCServer server;

    AX25MessageProcessor ax25MessageProcessor;
    AprsMessageProcessor aprsMessageProcessor;
    ControlCommandProcessor commandProcessor;

    PacketModem modem;

    public AX25MessageProcessor getAX25MessageProcessor() {
        return ax25MessageProcessor;
    }

    public AprsMessageProcessor getAprsMessageProcessor() {
        return aprsMessageProcessor;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public PacketModem getModem() {
        return modem;
    }

    public void setRadioMode(RadioMode mode) {
        this.radioMode = mode;
    }

    public void setMessageMode(MessageMode mode) {
        this.messageMode = mode;
    }

    public MessageMode getMessageMode() {
        return messageMode;
    }

    @Override
    public void onPacket(AX25Packet packet) {

        switch (packet.getType()) {

            case OTHER:
                System.err.println("unhandled packet: " + packet.toString());
                break;
            case AX25Chat:
                ax25MessageProcessor.process((AX25ChatPacket) packet);
                break;
            case APRS:
                aprsMessageProcessor.process((AX25APRSPacket) packet);
                break;
            case AX25FileTransfer:
                ax25MessageProcessor.process((AX25FileTransfer) packet);
                break;

        }

    }

    public IRCServer getServer() {
        return server;
    }

    @Override
    public void onClient(Client client) {
        client.addListener(this);
    }

    @Override
    public void onMessage(ServMessage message) {

        String dest = message.getParameters().get(0);

        if (!dest.startsWith("#")) {

            Client client = server.getClient(dest);

            // only RF direct messages to virtual clients.
            
            if (client == null || (client.getConnection() instanceof VirtualConnection)) {

                switch (messageMode) {
                    case APRS:
                        aprsMessageProcessor.onMessage(message);
                        break;
                    case AX25:
                        ax25MessageProcessor.onMessage(message);
                        break;
                }

            }

        }

    }

    public void configureServer() throws Exception {

        server = new IRCServer(listen_ip, listen_port);

        aprsMessageProcessor = new AprsMessageProcessor(server, modem);
        ax25MessageProcessor = new AX25MessageProcessor(server, modem);
        commandProcessor = new ControlCommandProcessor(this);

        server.addChannel("#APRS-RAW", new Channel(server, "#APRS-RAW", "", "Channel for unparsed APRS packets. Read-only.", 0));

        Channel aprs = new Channel(server, "#APRS", "", "Channel for decoded APRS packets.", 43);
        Channel aprsChat = new Channel(server, "#APRS-CHAT", "", "APRS messaging channel. Limited to 67 characters.", 67);
        Channel ax25Chat = new Channel(server, "#AX25-CHAT", "", "AX25 messaging channel. Limited to 254 characters.", 254);
        Channel controlChat = new Channel(server, "#CONTROL", "", "Control interface to the service. Type HELP.", 0);

        aprsChat.addListener(aprsMessageProcessor);
        aprs.addListener(aprsMessageProcessor);
        ax25Chat.addListener(ax25MessageProcessor);
        controlChat.addListener(commandProcessor);

        server.addChannel("#APRS", aprs);
        server.addChannel("#APRS-CHAT", aprsChat);
        server.addChannel("#AX25-CHAT", ax25Chat);
        server.addChannel("#CONTROL", controlChat);

        server.addClientConnectionListener(this);

        messageMode = MessageMode.APRS;

    }

    public void configureRadio() {

        switch (radioMode) {
            case KISS:
                modem = new KissEncoderDecoder(this, extra);
                break;
            case RTL_FM:
                modem = new RtlfmDecoder(this);
                break;
            case STDIN:
                modem = new StdinDecoder(this);
                break;
            case SOUND:
                modem = new SoundEncoderDecoder(this, extra);
                break;
        }

    }

    @Override
    public void run() {
        modem.start();
        server.run();
    }

    public void process() {

        while (!server.getSocket().isClosed()) {
            long time_s = System.nanoTime();

            Map<String, Client> clients = server.getClients();

            Iterator<Client> iter = clients.values().iterator();

            while (iter.hasNext()) {

                Client client = iter.next();

                if (client.getConnection() instanceof VirtualConnection && client.getLastActive() > 0 && client.getLastActive() < System.currentTimeMillis() - maxIdleTime) {
                    client.quitChans("idle time exceeded");
                    server.removeClient(client);
                }

            }

            server.processConnections();

            long time_e = System.nanoTime();
            int time_d = (int) ((time_e - time_s) / 1000000);

            server.setDeltaTime(time_d);

            try {
                Thread.sleep(Math.max(100 - time_d, 10));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    public static void main(String[] args) {

        if (args.length == 0) {
            System.out.println("Specify mode : kiss|stdin|rtlfm|sound");
            return;
        }

        try {

            AX25Irc ax25irc = new AX25Irc();

            String mode = args[0];

            if ("kiss".equalsIgnoreCase(mode)) {
                ax25irc.setRadioMode(RadioMode.KISS);
            } else if ("stdin".equalsIgnoreCase(mode)) {
                ax25irc.setRadioMode(RadioMode.STDIN);
            } else if ("rtlfm".equalsIgnoreCase(mode)) {
                ax25irc.setRadioMode(RadioMode.RTL_FM);
            } else if ("sound".equalsIgnoreCase(mode)) {
                ax25irc.setRadioMode(RadioMode.SOUND);
            } else {

                System.err.println("Uknown radio mode " + mode);
                System.exit(1);

            }

            if (args.length == 2) {
                ax25irc.setExtra(args[1]);
            }

            ax25irc.configureRadio();
            ax25irc.configureServer();
            ax25irc.start();
            ax25irc.process();

        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

}
