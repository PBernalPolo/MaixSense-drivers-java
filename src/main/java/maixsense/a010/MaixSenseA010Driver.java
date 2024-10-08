package maixsense.a010;


import java.nio.ByteBuffer;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;



/**
 * Controls the MaixSense-A010 ToF camera.
 * <p>
 * The driver is used to send commands over the {@link SerialPort}.
 * It also implements {@link SerialPortEventListener} to receive {@link MaixSenseA010Image}s over the {@link SerialPort}.
 * The images are always received, but the only way for a user to access them is:
 * <ul>
 *  <li> Implementing the {@link MaixSenseA010ImageConsumer} interface,
 *  <li> connecting a {@link MaixSenseA010ImageQueue} to this driver, and
 *  <li> adding the {@link MaixSenseA010ImageConsumer} as a listener in the {@link MaixSenseA010ImageQueue}.
 * </ul>
 * See {@link DefaultConfigurationOfMaixSenseA010} for an example.
 * <p>
 * The image consumption is implemented using the producer-consumer design pattern.
 * 
 * @see <a href>https://wiki.sipeed.com/hardware/en/maixsense/maixsense-a010/maixsense-a010.html</a>
 * @see <a href>https://wiki.sipeed.com/hardware/en/maixsense/maixsense-a010/at_command_en.html</a>
 */
public class MaixSenseA010Driver
    implements
        SerialPortEventListener
{
    ////////////////////////////////////////////////////////////////
    // PRIVATE CONSTANTS
    ////////////////////////////////////////////////////////////////
    
    /**
     * Minimum {@link #pixels} length value.
     */
    private static final int PIXELS_LENGTH_MIN = 25 * 25;
    
    /**
     * Maximum {@link #pixels} length value.
     */
    private static final int PIXELS_LENGTH_MAX = 100 * 100;
    
    /**
     * Represents the state of the finite-state machine in which we are receiving the first header byte.
     */
    private static final int RECEIVING_FIRST_HEADER_BYTE_STATE = 1;
    
    /**
     * Represents the state of the finite-state machine in which we are receiving the second header byte.
     */
    private static final int RECEIVING_SECOND_HEADER_BYTE_STATE = 2;
    
    /**
     * Represents the state of the finite-state machine in which we are receiving the packet length.
     */
    private static final int RECEIVING_PACKET_LENGTH_STATE = 3;
    
    /**
     * Represents the state of the finite-state machine in which we are receiving the packet info byte.
     */
    private static final int RECEIVING_PACKET_INFO_STATE = 4;
    
    /**
     * Represents the state of the finite-state machine in which we are receiving the packet pixels.
     */
    private static final int RECEIVING_PACKET_PIXELS_STATE = 5;
    
    /**
     * Represents the state of the finite-state machine in which we are receiving the checksum.
     */
    private static final int RECEIVING_CHECKSUM_STATE = 6;
    
    /**
     * Represents the state of the finite-state machine in which we are receiving the tail byte.
     */
    private static final int RECEIVING_TAIL_BYTE_STATE = 0;
    
    /**
     * Holds the bytes required to process data in each state of the finite-state machine.
     * That is:
     * <ul>
     *  <li> {@link #BYTES_NEEDED_FOR_STATE}[ {@link #RECEIVING_TAIL_BYTE_STATE} ] = 1 byte required to check if it is the tail byte.
     *  <li> {@link #BYTES_NEEDED_FOR_STATE}[ {@link #RECEIVING_FIRST_HEADER_BYTE_STATE} ] = 1 byte required to check if it is the first header byte.
     *  <li> {@link #BYTES_NEEDED_FOR_STATE}[ {@link #RECEIVING_SECOND_HEADER_BYTE_STATE} ] = 1 byte required to check if it is the second header byte.
     *  <li> {@link #BYTES_NEEDED_FOR_STATE}[ {@link #RECEIVING_PACKET_LENGTH_STATE} ] = 2 bytes required to compose the packet length.
     *  <li> {@link #BYTES_NEEDED_FOR_STATE}[ {@link #RECEIVING_PACKET_INFO_STATE} ] = 16 bytes required to build the packet info.
     *  <li> {@link #BYTES_NEEDED_FOR_STATE}[ {@link #RECEIVING_PACKET_PIXELS_STATE} ] = 1 byte required to add pixels sequentially.
     *  <li> {@link #BYTES_NEEDED_FOR_STATE}[ {@link #RECEIVING_CHECKSUM_STATE} ] = 1 byte required to compare against the computed checksum.
     * </ul>
     */
    private static final int[] BYTES_NEEDED_FOR_STATE = { 1 , 1 , 1 , 2 , 16 , 1 , 1 };
    
    
    
    ////////////////////////////////////////////////////////////////
    // PRIVATE VARIABLES
    ////////////////////////////////////////////////////////////////
    
    /**
     * {@link SerialPort} over which the communication is established.
     */
    private SerialPort serialPort;
    
    /**
     * {@link MaixSenseA010ImageQueue} used to queue the {@link MaixSenseA010Image}s.
     */
    private MaixSenseA010ImageQueue queue;
    
    /**
     * {@link ByteBuffer} used to dump the content of the {@link SerialPort}.
     * Dumping the content of the {@link SerialPort} to the {@link ByteBuffer} and then reading from such {@link ByteBuffer} is more efficient than reading directly from the {@link SerialPort} buffer.
     */
    private ByteBuffer byteBuffer;
    
    /**
     * State of the finite-state machine used to manage the image packet reception.
     * <p>
     * <ul>
     *  <li> 0: receiving first header byte.
     *  <li> 1: receiving second header byte.
     *  <li> 2: receiving packet length.
     *  <li> 3: receiving packet info.
     *  <li> 4: receiving packet pixels.
     *  <li> 5: receiving checksum.
     *  <li> 6: receiving tail byte.
     * </ul>
     */
    private int receivingState;
    
    /**
     * Counter used to keep track of the received pixels.
     */
    private int pixelCounter;
    
    /**
     * Exposure time received in the last image packet info.
     */
    private int exposureTimeReceived;
    
    /**
     * Frame Id received in the last image packet info.
     */
    private short frameIdReceived;
    
    //private byte commandReceived;
    //private byte outputModeReceived;
    
    /**
     * ToF sensor temperature received in the last image packet info.
     */
    private byte sensorTemperatureReceived;
    
    /**
     * Driver temperature received in the last image packet info.
     */
    private byte driverTemperatureReceived;
    
    /**
     * Error code received in the last image packet info.
     */
    private byte errorCodeReceived;
    
    //private byte reserved1ByteReceived;
    
    /**
     * Rows of the image received in the last image packet info.
     */
    private byte rowsReceived;
    
    /**
     * Columns of the image received in the last image packet info.
     */
    private byte colsReceived;
    
    //private byte ispVersionReceived;
    //private byte reserved3ByteReceived;
    
    /**
     * Checksum received in the last image packet.
     */
    private byte checksumComputed;
    
    /**
     * Array used to store the values of the pixels received in the last image packet info.
     */
    private byte[] pixels;
    
    /**
     * Used to define the value that is sent with the AT+DISP command.
     * Such command is used to enable/disable the LCD, USB, and UART communication.
     */
    private byte disp;
    
    
    
    ////////////////////////////////////////////////////////////////
    // PUBLIC CONSTRUCTORS
    ////////////////////////////////////////////////////////////////
    
    /**
     * Constructs a {@link MaixSenseA010Driver}.
     * 
     * @param serialPortPath    path to the serial port where the MaixSense-A010 is connected.
     */
    public MaixSenseA010Driver( String serialPortPath )
    {
        this.serialPort = new SerialPort( serialPortPath );
        // Create the ByteBuffer with a capacity that we don't expect to exceed.
        this.byteBuffer = ByteBuffer.allocateDirect( 1 << 16 );
        // Initialize receivingState.
        this.receivingState = 0;
        // Default initial value is LCD display on.
        this.disp = (byte)0b00000001;
    }
    
    
    
    ////////////////////////////////////////////////////////////////
    // PUBLIC METHODS
    ////////////////////////////////////////////////////////////////
    
    /**
     * Initializes communication through the serial port.
     * 
     * @throws SerialPortException  if the initialization fails.
     */
    public void initialize()
            throws SerialPortException
    {
        // Open serial port.
        this.serialPort.openPort();
        // Configuration must be set after opening the SerialPort.
        this.serialPort.setParams(
                SerialPort.BAUDRATE_115200 ,
                SerialPort.DATABITS_8 ,
                SerialPort.STOPBITS_1 ,
                SerialPort.PARITY_NONE );
        this.serialPort.addEventListener( this , SerialPort.MASK_RXCHAR );
    }
    
    
    /**
     * Terminates communication through the serial port.
     * 
     * @throws SerialPortException  if the termination fails.
     */
    public void terminate()
            throws SerialPortException
    {
        this.serialPort.removeEventListener();
        this.serialPort.purgePort( SerialPort.PURGE_RXCLEAR | SerialPort.PURGE_TXCLEAR );
        this.serialPort.closePort();
    }
    
    
    /**
     * Connects the queue in which the received images are stored.
     * 
     * @param imageQueue    queue in which the received images are stored.
     */
    public void connectQueue( MaixSenseA010ImageQueue imageQueue )
    {
        this.queue = imageQueue;
        this.queue.start();
    }
    
    
    /**
     * Activates Image Signal Processor.
     * <p>
     * "It is planned to start the module ISP, and the actual drawing needs to wait 1-2 seconds".
     */
    public void setImageSignalProcessorOn()
    {
        this.sendCommand( "AT+ISP=1\r" , "Failed setImageSignalProcessorOn." );
    }
    
    
    /**
     * Activates Image Signal Processor.
     * <p>
     * "Close the module ISP immediately, stop the IR transmitter".
     */
    public void setImageSignalProcessorOff()
    {
        this.sendCommand( "AT+ISP=0\r" , "Failed setImageSignalProcessorOff." );
    }
    
    
    /**
     * Sets the size of depth images to be received to 100 x 100 pixels^2.
     */
    public void setBinning100x100()
    {
        this.sendCommand( "AT+BINN=1\r" , "Failed setBinning100x100." );
    }
    
    
    /**
     * Sets the size of depth images to be received to 50 x 50 pixels^2.
     */
    public void setBinning50x50()
    {
        this.sendCommand( "AT+BINN=2\r" , "Failed setBinning50x50." );
    }
    
    
    /**
     * Sets the size of depth images to be received to 25 x 25 pixels^2.
     */
    public void setBinning25x25()
    {
        this.sendCommand( "AT+BINN=4\r" , "Failed setBinning25x25." );
    }
    
    
    /**
     * Activates LCD display.
     */
    public void setLcdDisplayOn()
    {
        this.disp |= (byte)0b00000001;
        this.updateDisp( "Failed setLcdDisplayOn." );
    }
    
    
    /**
     * Deactivates LCD display.
     */
    public void setLcdDisplayOff()
    {
        this.disp &= (byte)0b11111110;
        this.updateDisp( "Failed setLcdDisplayOff." );
    }
    
    
    /**
     * Activates USB communication.
     */
    public void setUsbDisplayOn()
    {
        this.disp |= (byte)0b00000010;
        this.updateDisp( "Failed setUsbDisplayOn." );
    }
    
    
    /**
     * Deactivates USB communication.
     */
    public void setUsbDisplayOff()
    {
        this.disp &= (byte)0b11111101;
        this.updateDisp( "Failed setUsbDisplayOff." );
    }
    
    
    /**
     * Activates UART communication.
     */
    public void setUartDisplayOn()
    {
        this.disp |= (byte)0b00000100;
        this.updateDisp( "Failed setUartDisplayOn." );
    }
    
    
    /**
     * Deactivates UART communication.
     */
    public void setUartDisplayOff()
    {
        this.disp &= (byte)0b11111011;
        this.updateDisp( "Failed setUartDisplayOff." );
    }
    
    
    /**
     * Sets the UART baudrate to 9600.
     */
    public void setUartBaudrate9600()
    {
        this.sendCommand( "AT+BAUD=0\r" , "Failed setUartBaudrate9600." );
    }
    
    
    /**
     * Sets the UART baudrate to 57600.
     */
    public void setUartBaudrate57600()
    {
        this.sendCommand( "AT+BAUD=1\r" , "Failed setUartBaudrate57600." );
    }
    
    
    /**
     * Sets the UART baudrate to 115200.
     */
    public void setUartBaudrate115200()
    {
        this.sendCommand( "AT+BAUD=2\r" , "Failed setUartBaudrate115200." );
    }
    
    
    /**
     * Sets the UART baudrate to 230400.
     */
    public void setUartBaudrate230400()
    {
        this.sendCommand( "AT+BAUD=3\r" , "Failed setUartBaudrate230400." );
    }
    
    
    /**
     * Sets the UART baudrate to 460800.
     */
    public void setUartBaudrate460800()
    {
        this.sendCommand( "AT+BAUD=4\r" , "Failed setUartBaudrate460800." );
    }
    
    
    /**
     * Sets the UART baudrate to 921600.
     */
    public void setUartBaudrate921600()
    {
        this.sendCommand( "AT+BAUD=5\r" , "Failed setUartBaudrate921600." );
    }
    
    
    /**
     * Sets the UART baudrate to 1000000.
     */
    public void setUartBaudrate1000000()
    {
        this.sendCommand( "AT+BAUD=6\r" , "Failed setUartBaudrate1000000." );
    }
    
    
    /**
     * Sets the UART baudrate to 2000000.
     */
    public void setUartBaudrate2000000()
    {
        this.sendCommand( "AT+BAUD=7\r" , "Failed setUartBaudrate2000000." );
    }
    
    
    /**
     * Sets the UART baudrate to 3000000.
     */
    public void setUartBaudrate3000000()
    {
        this.sendCommand( "AT+BAUD=8\r" , "Failed setUartBaudrate3000000." );
    }
    
    
    /**
     * Sets the quantization unit.
     * <p>
     * The quantization unit is used to compute the pixel value from a depth measurement as described in
     * <a href>https://wiki.sipeed.com/hardware/en/maixsense/maixsense-a010/at_command_en.html#UNIT-directive</a>.
     * That is,
     * <ul>
     *  <li> If quantization unit is 0,  pixelValue = 5.1 * sqrt( depth )
     *  <li> Otherwise,  pixelValue = depth / quantizationUnit
     * </ul>
     * 
     * @param unit  quantization unit used to compute the pixel value from depth measurements.
     */
    public void setQuantizationUnit( int unit )
    {
        if(  0 <= unit  &&  unit <= 9  ) {
            this.sendCommand( "AT+UNIT=" + unit + "\r" , "Failed setQuantizationUnit." );
        } else {
            this.sendCommand( "AT+UNIT=0\r" , "Failed setQuantizationUnit." );
            System.out.println( "Quantization unit must be in [0,10] interval; unit set to 0." );
        }
    }
    
    
    /**
     * Sets the Frames Per Second.
     * <p>
     * Input values must be in the range [1,20].
     * 
     * @param fps   Frames Per Second.
     */
    public void setFps( int fps )
    {
        if( fps < 1 ) {
            fps = 1;
            System.out.println( "fps must be in the interval [1,20]; fps set to 1." );
        }
        if( fps > 20 ) {
            fps = 20;
            System.out.println( "fps must be in the interval [1,20]; fps set to 20." );
        }
        this.sendCommand( "AT+FPS=" + fps + "\r" , "Failed setFps." );
    }
    
    
    /**
     * Activates the anti-multi-machine interference.
     */
    public void setAntiMultiMachineInterferenceOff()
    {
        this.sendCommand( "AT+ANTIMMI=0\r" , "Failed setAntiMultiMachineInterferenceOff." );
    }
    
    
    /**
     * Deactivates the anti-multi-machine interference.
     */
    public void setAntiMultiMachineInterferenceOn()
    {
        this.sendCommand( "AT+ANTIMMI=1\r" , "Failed setAntiMultiMachineInterferenceOn." );
    }
    
    
    /**
     * Activates the auto exposure time algorithm.
     * <p>
     * The exposure time will be automatically set depending on the environment conditions.
     */
    public void setExposureTimeAutoOn()
    {
        this.sendCommand( "AT+AE=1\r" , "Failed setExposureTimeAuto AE." );
        //this.sendCommand( "AT+EV=0\r" , "Failed setExposureTimeAuto EV." );
    }
    
    
    /**
     * Deactivates the auto exposure time algorithm.
     * <p>
     * The exposure time will take the last value set by the auto-exposure algorithm.
     */
    public void setExposureTimeAutoOff()
    {
        this.sendCommand( "AT+AE=0\r" , "Failed setExposureTimeAuto AE." );
    }
    
    
    /**
     * Sets the exposure time.
     * <p>
     * The exposure time is fixed, and its value is the input value.
     * 
     * @param value     exposure time.
     */
    // TODO: implement working setExposureTime
    /*public void setExposureTime( int value )
    {
        if(  value < 1  ||  40000 < value  ) {
            value = 1;
            System.out.println( "Exposure time must be in the interval [1,40000]; exposure value set to 1." );
        }
        this.sendCommand( "AT+AE=0\r" , "Failed setExposureTime AE." );
        this.sendCommand( "AT+EV=" + value + "\r" , "Failed setExposureTime EV." );
    }*/
    
    
    /**
     * Saves current configuration in MaixSense-A010 ToF camera.
     */
    public void saveConfiguration()
    {
        this.sendCommand( "AT+SAVE\r" , "Failed saveConfiguration." );
    }
    
    
    /**
     * Executed whenever there is a serial event.
     */
    @Override
    public void serialEvent( SerialPortEvent event )
    {
        if( event.isRXCHAR() ) {
            try {
                this.updateFromInputBytes();
            } catch( SerialPortException e ) {
                this.receivingState = RECEIVING_TAIL_BYTE_STATE;
                e.printStackTrace();
            }
        }
    }
    
    
    
    ////////////////////////////////////////////////////////////////
    // PRIVATE METHODS
    ////////////////////////////////////////////////////////////////
    
    /**
     * Sends an AT command through the serial port.
     * 
     * @param atCommand     AT command to be sent.
     * @param errorMessageOnFailure     error message printed if there is a failure on sending the AT command.
     * @return  true if the AT command is successfully sent; false otherwise.
     */
    private void sendCommand( String atCommand , String errorMessageOnFailure )
    {
        try {
            this.serialPort.writeString( atCommand );
        } catch( SerialPortException e ) {
            System.out.println( errorMessageOnFailure );
        }
    }
    
    
    /**
     * Sends a display command to activate/deactivate the communication through LCD display, USB, or UART.
     * 
     * @param errorMessageOnFailure     error message printed if there is a failure on sending the AT command.
     */
    private void updateDisp( String errorMessageOnFailure )
    {
        this.sendCommand( "AT+DISP=" + this.disp + "\r" , errorMessageOnFailure );
    }
    
    
    /**
     * Implements the finite-state machine that manages the image packet reception.
     * 
     * @throws SerialPortException  from {@link SerialPort#readBytes(int)} or {@link SerialPort#getInputBufferBytesCount()}.
     */
    private void updateFromInputBytes()
            throws SerialPortException
    {
        // This method is called after a serialEvent in which the event isRXCHAR, so we have for sure received bytes.
        this.byteBuffer.put( this.serialPort.readBytes() );
        // Switch to reading mode.
        this.byteBuffer.flip();
        // Update state.
        while( this.byteBuffer.remaining() >= BYTES_NEEDED_FOR_STATE[ this.receivingState ] ) {
            switch( this.receivingState ) {
                case RECEIVING_FIRST_HEADER_BYTE_STATE:
                    this.receiveFirstHeaderByteBehavior();
                    break;
                case RECEIVING_SECOND_HEADER_BYTE_STATE:
                    this.receiveSecondHeaderByteBehavior();
                    break;
                case RECEIVING_PACKET_LENGTH_STATE:
                    this.receivePacketLengthBehavior();
                    break;
                case RECEIVING_PACKET_INFO_STATE:
                    this.receivePacketInfoBehavior();
                    break;
                case RECEIVING_PACKET_PIXELS_STATE:
                    this.receivePixelsBehavior();
                    break;
                case RECEIVING_CHECKSUM_STATE:
                    this.receiveChecksumBehavior();
                    break;
                case RECEIVING_TAIL_BYTE_STATE:
                    this.receiveTailByteBehavior();
                    break;
                default:
                    // If we are in some other state, go to receive tail byte.
                    this.receivingState = RECEIVING_TAIL_BYTE_STATE;
            }
        }
        // Switch back to writing mode.
        this.byteBuffer.compact();
    }
    
    
    /**
     * Implements behavior of finite-state machine when first header byte is being received.
     * 
     * @see #updateFromInputBytes()
     */
    private void receiveFirstHeaderByteBehavior()
    {
        // Get next byte.
        byte nextByte = this.byteBuffer.get();
        // If the received byte is 0x00, then we go to receive second header byte.
        if( nextByte == (byte)0x00 ) {
            // Go to receive second header byte.
            this.receivingState = RECEIVING_SECOND_HEADER_BYTE_STATE;
        } else {
            // If not, fall back to receive tail byte.
            this.receivingState = RECEIVING_TAIL_BYTE_STATE;
        }
    }
    
    
    /**
     * Implements behavior of finite-state machine when second header byte is being received.
     * 
     * @see #updateFromInputBytes()
     */
    private void receiveSecondHeaderByteBehavior()
    {
        // Get next byte.
        byte nextByte = this.byteBuffer.get();
        // If the received byte is 0xFF, then we go to receive packet length.
        if( nextByte == (byte)0xFF ) {
            // Initialize checksum.
            this.checksumComputed = (byte)0xFF;
            // Go to receive packet length.
            this.receivingState = RECEIVING_PACKET_LENGTH_STATE;
        } else {
            // If not 0xFF, go back to receive tail byte.
            this.receivingState = RECEIVING_TAIL_BYTE_STATE;
        }
    }
    
    
    /**
     * Implements behavior of finite-state machine when packet length is being received.
     * 
     * @see #updateFromInputBytes()
     */
    private void receivePacketLengthBehavior()
    {
        // Get 2 bytes.
        byte[] nextBytes = new byte[ BYTES_NEEDED_FOR_STATE[ RECEIVING_PACKET_LENGTH_STATE ] ];
        this.byteBuffer.get( nextBytes );
        // Build packet length from them.
        int receivedLength = ( ( nextBytes[1] << 8 ) | (nextBytes[0] & 0xFF) );
        // Compute number of pixels to receive.
        int pixelsLength = receivedLength - BYTES_NEEDED_FOR_STATE[ RECEIVING_PACKET_INFO_STATE ];
        // Check that pixelsLength is within bounds.
        if(  PIXELS_LENGTH_MIN <= pixelsLength  &&  pixelsLength <= PIXELS_LENGTH_MAX  ) {
            // Update pixels variable if necessary.
            if(  this.pixels == null  ||  this.pixels.length != pixelsLength  ) {
                this.pixels = new byte[ pixelsLength ];
            }
            // Update checksum.
            this.checksumComputed += nextBytes[0];
            this.checksumComputed += nextBytes[1];
            // And go to receive the packet info.
            this.receivingState = RECEIVING_PACKET_INFO_STATE;
        } else {
            // If pixelsLength is not in bounds, go to receive tail byte.
            this.receivingState = RECEIVING_TAIL_BYTE_STATE;
        }
    }
    
    
    /**
     * Implements behavior of finite-state machine when packet info is being received.
     * 
     * @see #updateFromInputBytes()
     */
    private void receivePacketInfoBehavior()
    {
        // Get next 16 bytes.
        byte[] nextBytes = new byte[ BYTES_NEEDED_FOR_STATE[ RECEIVING_PACKET_INFO_STATE ] ];
        this.byteBuffer.get( nextBytes );
        // Extract the info according to the documentation.
        //this.commandReceived = nextBytes[0];
        //this.outputModeReceived = nextBytes[1];
        this.sensorTemperatureReceived = nextBytes[2];
        this.driverTemperatureReceived = nextBytes[3];
        this.exposureTimeReceived = (
                ( nextBytes[7] << 24 ) |
                ( (nextBytes[6] & 0xFF) << 16 ) |
                ( (nextBytes[5] & 0xFF) << 8 ) |
                (nextBytes[4] & 0xFF) );
        this.errorCodeReceived = nextBytes[8];
        //this.reserved1ByteReceived = nextBytes[9];
        this.rowsReceived = nextBytes[10];
        this.colsReceived = nextBytes[11];
        short frameIdNew = (short)( ( nextBytes[13] << 8 ) | (nextBytes[12] & 0xFF) );
        // Sometimes same image is received multiple times.
        // If this happens, we save resources by ignoring the rest of the package.
        if( frameIdNew != this.frameIdReceived ) {
            this.frameIdReceived = frameIdNew;
        } else {
            this.receivingState = RECEIVING_TAIL_BYTE_STATE;
            return;
        }
        //this.ispVersionReceived = byteRead[14];
        //this.reserved3ByteReceived = byteRead[15];
        // Update checksum.
        for( int i=0; i<BYTES_NEEDED_FOR_STATE[ RECEIVING_PACKET_INFO_STATE ]; i++ ) {
            this.checksumComputed += nextBytes[i];
        }
        // Initialize pixel counter.
        this.pixelCounter = 0;
        // And go to receive pixels.
        this.receivingState = RECEIVING_PACKET_PIXELS_STATE;
    }
    
    
    /**
     * Implements behavior of finite-state machine when packet pixels are being received.
     * 
     * @see #updateFromInputBytes()
     */
    private void receivePixelsBehavior()
    {
        // Iterate while there are bytes to read and the pixels are still not complete.
        int bytesToRead = this.byteBuffer.remaining();
        int bytesToCompletion = this.pixels.length - this.pixelCounter;
        /* Store in bytesToRead the minimum of bytesToRead and bytesToCompletion.
         * This makes that we don't read more bytes than bytes that are in the buffer (bytesToRead is minimum),
         * or more bytes than bytes needed to complete the pixels.
         */
        if( bytesToRead > bytesToCompletion ) {
            bytesToRead = bytesToCompletion;
        }
        // Read the bytes.
        byte[] nextBytes = new byte[ bytesToRead ];
        this.byteBuffer.get( nextBytes );
        // Iterate over the bytes.
        for( int i=0; i<nextBytes.length; i++ ) {
            // Store the byte.
            this.pixels[ this.pixelCounter ] = nextBytes[i];
            this.pixelCounter++;
            // Update the checksum.
            this.checksumComputed += nextBytes[i];
        }
        // If we completed the pixels,
        if( this.pixelCounter == this.pixels.length ) {
            // go to receive the checksum.
            this.receivingState = RECEIVING_CHECKSUM_STATE;
        }
    }
    
    
    /**
     * Implements behavior of finite-state machine when checksum is being received.
     * 
     * @see #updateFromInputBytes()
     */
    private void receiveChecksumBehavior()
    {
        // Get next byte.
        byte nextByte = this.byteBuffer.get();
        // If the checksum computed matches the received byte,
        if( this.checksumComputed == nextByte ) {
            // create image and add it to the queue (if connected),
            if( this.queue != null ) {
                MaixSenseA010Image image = this.getImageCurrent();
                this.queue.add( image );
            }
        }
        // Whatever the result of the checksum, we go to receive the tail byte.
        this.receivingState = RECEIVING_TAIL_BYTE_STATE;
    }
    
    
    /**
     * Implements behavior of finite-state machine when tail byte is being received.
     * 
     * @see #updateFromInputBytes()
     */
    private void receiveTailByteBehavior()
    {
        // Get next byte.
        byte nextByte = this.byteBuffer.get();
        // If the received byte is 0xDD,
        if( nextByte == (byte)0xDD ) {
            // go to receive first header byte.
            this.receivingState = RECEIVING_FIRST_HEADER_BYTE_STATE;
        }
    }
    
    
    /**
     * Creates image from last image packet.
     * 
     * @return  image from last image packet.
     */
    private MaixSenseA010Image getImageCurrent()
    {
        MaixSenseA010Image output = new MaixSenseA010Image( this.rowsReceived , this.colsReceived , this.pixels.clone() );
        output.setFrameId( this.frameIdReceived );
        output.setExposureTime( this.exposureTimeReceived );
        output.setSensorTemperature( this.sensorTemperatureReceived );
        output.setDriverTemperature( this.driverTemperatureReceived );
        output.setErrorCode( this.errorCodeReceived );
        return output;
    }
    
}
