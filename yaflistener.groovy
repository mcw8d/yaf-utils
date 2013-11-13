@Grab(group='io.netty', module='netty-all', version='4.0.7.Final')

import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.Channel
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.buffer.ByteBuf
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.ChannelOption
import io.netty.channel.FixedRecvByteBufAllocator
import io.netty.channel.ChannelInitializer
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.ByteToMessageDecoder
import java.math.BigInteger

public class IpfixTemplateField {
    
    Integer fieldLength 
    Boolean isVariableLength
    Integer fieldIndex
    String fieldName
}

public class IpfixTemplate {

    List fieldList = []
    Integer fieldCount
}

public class StringHandler extends ChannelInboundHandlerAdapter {

     def ieStore = new XmlParser().parse(new File("InformationElements.xml"))
     def templates = [:]
     def templateRecordCounts = [:]

     @Override
     public void channelRead(ChannelHandlerContext ctx, message) {
         if (message instanceof ByteBuf) {
             println "New Message of length: ${message.readableBytes()}"
             println "Message buffer has a max capacity of: ${message.capacity()}"
             readMessage(message)
             println "\n\n\n***** COUNTS *****"
             templateRecordCounts.each { key, value ->
                 println "${key}: $value"
             }
         }
     }

     def readMessage(ByteBuf msg) {
         println "IPFIX Version: ${msg.readUnsignedShort()} | Length: ${msg.readUnsignedShort()}"
         println "Export time: ${(new Date(msg.readUnsignedInt() * 1000)).toString()}"
         println "Sequence #: ${msg.readUnsignedInt()}"
         println "Obs Domain Id: ${msg.readUnsignedInt()}"
         while (msg.readableBytes() > 0) {
             Integer setId = msg.readUnsignedShort()
             Integer length = msg.readUnsignedShort()
             println "\tSet ID: ${setId} | Length: ${length}"
             switch(setId) {
                 case 2:
                     readTemplateRecords(msg.readBytes(length - 4))                 
                     break
                 case 3:
                     readOptionsTemplateRecords(msg.readBytes(length - 4))
                     break
                 case 10:
                     println "Assuming new ipfix message and starting over"
                     readMessage(msg.readerIndex(msg.readerIndex() - 4))
                     break
                 case { it > 255 }:
                     readDataRecords(setId, msg.readBytes(length - 4))
                     break
                 default:
                     println "ERROR! Incorrect set id found. Throwing away message!"
                     return
             }
         }
     }

     def readTemplateRecords(ByteBuf msg) {
         while(msg.readableBytes() > 0) {
             Integer templateId = msg.readUnsignedShort()
             if (templateId == 0) return
             Integer fieldCount = msg.readUnsignedShort()
             println "\t\tTemplateId: ${templateId} | Field Count: ${fieldCount}"
             def ipfixTemplate = new IpfixTemplate(fieldCount: fieldCount)
             Integer index = 0
             Integer offset = 0
             while(index < fieldCount) {
                 Integer elemId = msg.readUnsignedShort()
                 Boolean isEnterprise = elemId >= 32768
                 elemId -= isEnterprise ? 32768 : 0
                 Integer fLength = msg.readUnsignedShort()
                 Long pen = isEnterprise ? msg.readUnsignedInt() : 0
                 println "\t\t\tEnterprise: ${isEnterprise} | ElementId: ${elemId} | Field Length: ${fLength}"
                 println "\t\t\tEnterprise Number: ${pen}"
                 def field = ieStore.field.find { it.pen.text().toLong() == pen && it.elemId.text().toInteger() == elemId }
                 println "\t\t\tName: ${field?.name?.text()}"
                 def templateField = new IpfixTemplateField(fieldLength: fLength, isVariableLength: (fLength == 65535), fieldIndex: index, fieldName: field?.name?.text())
                 ipfixTemplate.fieldList << templateField
                 index++
             }
             templates[templateId] = ipfixTemplate
         }
     }

     def readOptionsTemplateRecords(ByteBuf msg) {
     }

     def readDataRecords(Integer setId, ByteBuf msg) {
         def ipfixTemplate = templates[setId]
         def templateRecordCount = templateRecordCounts.get(setId, 0)
         if (!ipfixTemplate) return
         println "\tDataRecords for Template: ${setId}"
         while (msg.readableBytes() > 0) {
             print "\t\t"
             ipfixTemplate.fieldList.each { field ->
                 print "Field: ${field.fieldName ?: 'Unk'} | "
                 if (!field.isVariableLength) {
                     switch(field.fieldLength) {
                         case 1:
                             print msg.readUnsignedByte()
                             break
                         case 2:
                             print msg.readUnsignedShort()
                             break
                         case 4:
                             print msg.readUnsignedInt()
                             break
                         case 8:
                             def temp = new byte[9]
                             temp[0] = new Byte('0')
                             try { msg.readBytes(temp, 1, 8) } catch (Exception e) { println e.message }
                             def temp3 = new BigInteger(temp)
                             print temp3.toString()
                             break
                         default:
                             print "Oh dear! $field.fieldLength"
                             msg.readBytes(field.fieldLength)
                     }
                 } else {
                     print "VarLengthSkip"
                     def length = msg.readUnsignedByte()
                     if (length < 255) { msg.readBytes(length) }
                     else {
                         length = msg.readUnsignedShort()
                         msg.readBytes(length)
                     }
                 }
                 print "\t"
             }
             print "\n"
             templateRecordCount++
         }
         templateRecordCounts[setId] = templateRecordCount
     }
}

public class UDPHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
        println "Got message from: ${msg.sender()}"
        ctx.fireChannelRead(msg.content())
    }
}

public class MyChannelInitializer extends ChannelInitializer {
    public void initChannel(Channel channel) {
        channel.pipeline().addLast("UDPHandler", new UDPHandler())
        //channel.pipeline().addLast("FrameDecoder", new LengthFieldBasedFrameDecoder(65535, 2, 2, -4, 0))
        channel.pipeline().addLast("StringDecoder", new StringHandler())
    }
}

/*def channel = new ServerBootstrap()
	.group(new NioEventLoopGroup(), new NioEventLoopGroup())
	.channel(NioServerSocketChannel.class)
	.childHandler(new MyChannelInitializer())
	.bind(4380)
	.sync()
	.channel()*/

def channel = new Bootstrap()
        .group(new NioEventLoopGroup())
        .channel(NioDatagramChannel.class)
        .handler(new MyChannelInitializer())
        .bind(4379)
        .sync()
        .channel()

println "Bound to ${channel.localAddress().getPort()}"
def config = channel.config().getOptions()

println "Started my channel:"
config.each { cOption, value ->
    println "${cOption.name()} : ${value.toString()}"
}
