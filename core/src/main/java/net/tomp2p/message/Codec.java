/*
 * Copyright 2009 Thomas Bocek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package net.tomp2p.message;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.Arrays;

import net.tomp2p.crypto.ChaCha20;
import net.tomp2p.crypto.Crypto;
import net.tomp2p.p2p.PeerAddressManager;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.utils.Pair;
import net.tomp2p.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.tomp2p.message.Message.ProtocolType;
import net.tomp2p.peers.Number256;
import org.whispersystems.curve25519.Curve25519KeyPair;

/**
 * Encodes and decodes the header of a {@code Message} sing a Netty Buffer.
 * 
 * @author Thomas Bocek
 * 
 */
public final class Codec {

    private static final Logger LOG = LoggerFactory.getLogger(Codec.class);

    public static final int HEADER_SIZE_MIN = 156;

    /**
     * Encodes a message object. First the
     * 
     * The format looks as follows: 
     *  - 2bit protocol type 00 is UDP, 01 is KCP, 10 and 11 planned to have different KCP
     *    (don't care about lost packets, or high priority, at the moment its not used). If its KCP, then then the next
     *    30bits are the session Id, followed by the rest of the KCP header.
     *  - 30bit p2p version **4 bytes** //ignore if its wrong p2p version
     *  - 32bit message id **4 bytes**
     *  - public key of sender xored with public key of recipient with overlapping 28 bytes **36 bytes**
     *  - Epheral public key of sender, just for this message. **32 bytes**
     *  -- now starts the encrypted part with ChaCha20-- - 32bytes sym key based on key pairs sender receiver **12 bytes** nonce overhead from ChaCha20
     *  - peeraddress of sender (without IP or public key) **min. 2 bytes**
     *  - 4bit message type 	(request, ack, ok)
     *  - 4bit message options  **1 byte**
     *  - 8bit message command  **1 byte**
     *  - nbit data, can be 0 or up to packet size
     *  - 64 bytes ED signature **64 bytes**
     *  
     *  It total, the header is minimum of size 156 bytes.
     *
     * @param message
     *            The message with the header that will be encoded
     * @return The buffer passed as an argument
     */
    public static void encode(final ByteBuffer buf, final Message message, final PeerAddressManager lookup, final byte[] ephemeralPublicKeyRemote, final boolean encodeForIPv4) throws GeneralSecurityException, IOException {

        if(buf.remaining() < Codec.HEADER_SIZE_MIN) {
            throw new IOException("header too small, min size is " + Codec.HEADER_SIZE_MIN);
        }

    	final int versionAndType = message.protocolType().ordinal() << 30 | (message.version() & 0x3fffffff);
    	buf.putInt(versionAndType); //4
        buf.putInt(message.messageId()); // 8

        final byte[] xored = message.sender().peerId().xorOverlappedBy4(message.recipient().peerId());
        buf.put(xored); //44
        final Curve25519KeyPair pair = message.ephemeralKeyPair();
        buf.put(pair.getPublicKey()); //76

        final int payloadLength;
        if(message.payload() != null) {
            final ByteBuffer payload = message.payload().asReadOnlyBuffer();
            payloadLength=payload.remaining();
        } else {
            payloadLength = 0;
        }
        final int maxEncryptionSize = PeerAddress.MAX_SIZE_NO_PEER_ID+4+1+1+payloadLength+12;
        final ByteBuffer needsEncryption = ByteBuffer.allocate(maxEncryptionSize);

        //peer ID is in the xored section, and the IP comes with the IP packet, so no need for encoding
        PeerAddress tmp = message.sender().withSkipPeerId(true);
        if(encodeForIPv4) {
            tmp = tmp.withIpv4Flag(false);
        } else {
            tmp = tmp.withIpv6Flag(false);
        }

        tmp.encode(needsEncryption);

        needsEncryption.put((byte) (message.type().ordinal() << 4 | message.options()));
        needsEncryption.put(message.command());
        if(message.payload() != null) {
            final ByteBuffer payload = message.payload().asReadOnlyBuffer();
            needsEncryption.put(payload); //copy
        }

        //if there is a public key in the message, then encrypt with the empheral private key, as this message is a
        //reply to a request. If no ephemeral key is present, its a request and the public key of the recipient
        //has to be used in order to be 0RTT
        byte[] publicKey = ephemeralPublicKeyRemote != null ? ephemeralPublicKeyRemote : message.recipient().peerId().toByteArray();
        final byte[] sharedKey = Crypto.cipher.calculateAgreement(publicKey, pair.getPrivateKey());
        LOG.debug("shared key encoding: {}", Arrays.toString(sharedKey));
        LOG.debug("public key emp encoding: {}", Arrays.toString(pair.getPublicKey()));
        LOG.debug("public key rem encoding: {}", Arrays.toString(message.recipient().peerId().toByteArray()));

        ChaCha20 cc20 = new ChaCha20(sharedKey);
        needsEncryption.flip();
        cc20.encrypt(buf, needsEncryption);

        //sign with ED25519
        buf.flip(); //read mode
        byte[] msg=new byte[buf.limit()];
        buf.get(msg);
        //add signature
        byte[] privateKey = lookup.getPeerAddressFromId(message.sender().peerId()).e1();
        byte[] sig = Crypto.cipher.calculateSignature(privateKey, msg);
        LOG.debug("signature encoding: {}", Arrays.toString(sig));
        buf.limit(buf.capacity()); //write mode
        buf.put(sig);
    }
    
    public static ProtocolType peekProtocolType(int versionAndType) {
    	return ProtocolType.values()[versionAndType >>> 30];
    }
    
    public static ProtocolType peekProtocolType(byte version) {
    	return ProtocolType.values()[version >>> 6];
    }


    /**
     * Decodes a message object.
     * 
     * The format looks as follows: 28bit p2p version - 4bit message type - 32bit message id - 8bit message command - 160bit
     * sender id - 16bit sender tcp port - 16bit sender udp port - 160bit recipient id - 32bit content types - 8bit options. It total,
     * the header is of size 58 bytes.
     * 
     * @param buffer
     *            The buffer to decode from
     * @return The partial message where only the header fields are set
     */
    public static MessageHeader decodeHeader(final ByteBuffer buffer, final PeerAddressManager lookup) throws IOException {

        if(buffer.remaining() < Codec.HEADER_SIZE_MIN) {
            throw new IOException("header too small, min size is " + Codec.HEADER_SIZE_MIN);
        }

        MessageHeader.MessageHeaderBuilder messageHeaderBuilder = new MessageHeader.MessageHeaderBuilder();

        final int versionAndType = buffer.getInt();
        messageHeaderBuilder.version(versionAndType & 0x3fffffff);
        messageHeaderBuilder.messageId(buffer.getInt());

        final byte[] xored = new byte[36];
        buffer.get(xored);
        final int recipientShortId = Utils.byteArrayToInt(xored, 32);
        final int senderIdShort = Utils.byteArrayToInt(xored, 0);
        final Pair<PeerAddress, byte[]> recipientId = lookup.getPeerAddressFromShortId(recipientShortId);
        messageHeaderBuilder.recipient(recipientId.e0());
        messageHeaderBuilder.privateKey(recipientId.e1());
        final Number256 senderId = recipientId.element0().peerId().deXorOverlappedBy4(xored, senderIdShort);
        messageHeaderBuilder.senderId(senderId);

        return messageHeaderBuilder.build();

    }

    public static void decodePayload(final ByteBuffer buffer, final Message message, final MessageHeader messageHeader, final byte[] ephemeralPrivateKeyLocal,
                                    final InetSocketAddress local, final InetSocketAddress remote) throws GeneralSecurityException {

        message.recipientSocket(local);
        message.senderSocket(remote);
        message.version(messageHeader.version());
        message.messageId(messageHeader.messageId());
        message.recipient(messageHeader.recipient());
        final Number256 senderId = messageHeader.senderId();
        final byte[] localPrivateKey = messageHeader.privateKey();
        //messageHeader not used anymore

        byte[] ephemeralPublicKey = new byte[32];
        buffer.get(ephemeralPublicKey);
        message.ephemeralPublicKey(ephemeralPublicKey);

        //if there is a private key for the message, then encrypt with the empheral private key, as this message is a
        //reply to a request. If no ephemeral key is present, its a request and the public key of the recipient
        //has to be used in order to be 0RTT
        byte[] privateKey = ephemeralPrivateKeyLocal != null ? ephemeralPrivateKeyLocal : localPrivateKey;
        byte[] sharedKey = Crypto.cipher.calculateAgreement(ephemeralPublicKey, privateKey);

        LOG.debug("shared key decoding: {}", Arrays.toString(sharedKey));
        LOG.debug("public key emp decoding: {}", Arrays.toString(ephemeralPublicKey));
        LOG.debug("public key loc decoding: {}", Arrays.toString(message.recipient().peerId().toByteArray()));
        LOG.debug("priv: {}", ephemeralPrivateKeyLocal);

        int payloadLength = buffer.remaining() - 64;
        byte[] encrypted = new byte[payloadLength];
        buffer.get(encrypted);

        ChaCha20 cc20 = new ChaCha20(sharedKey);
        byte[] plainText = cc20.decrypt(encrypted);
        ByteBuffer plain = ByteBuffer.wrap(plainText);

        //add sender information, from both socket and the data in the packet
        PeerAddress sender = PeerAddress.decode(plain);
        PeerSocketAddress psa = PeerSocketAddress.create(remote);
        sender = sender.withIPSocket(psa);
        sender = sender.withPeerId(senderId).withSkipPeerId(false);
        message.sender(sender);
        int messageOptions = plain.get();
        message.type(Message.Type.values()[messageOptions >>> 4]);
        message.options(messageOptions & 0xf);
        message.command(plain.get());
        message.payload(plain);

        byte[] sig = new byte[64];
        buffer.get(sig);
        LOG.debug("signature decoding: {}", Arrays.toString(sig));

        buffer.flip();
        int totalLength = buffer.remaining() - 64;
        byte[] raw = new byte[totalLength];
        buffer.get(raw);

        message.setDone(Crypto.cipher.verifySignature(senderId.toByteArray(), raw, sig) );
    }
}
