package Extensions;
import Conditions.CCndExtension;
import Actions.CActExtension;
import Application.CRunApp;
import Application.CRunApp.MenuEntry;
import Expressions.CValue;
import RunLoop.CCreateObjectInfo;
import Runtime.MMFRuntime;
import Services.CBinaryFile;
import Objects.CObject;
import Services.CServices;
import Runtime.Log;

import java.nio.charset.Charset;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
 
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

 
/**
 * This class is used for talking to hid device, connecting, disconnecting and enumerating the devices.
 * @author conceptgame
 */
public class CRunHIDDevice extends CRunExtension
{
	private static final String ACTION_USB_PERMISSION =
		    "com.example.company.app.hiddevice.USB_PERMISSION";
	
	// Locker object that is responsible for locking read/write thread.
	private Object _locker = new Object();
	private Thread _readingThread = null;
	private String _deviceName;
	
	private UsbManager _usbManager;
	private UsbDevice _usbDevice;
	
	// The queue that contains the read data.
	private Queue<byte[]> _receivedQueue;
	// List containing all usb devices after enumeration
	HashMap<String, UsbDevice> _deviceList;
	private int _curId;
	private String _lastError;
	
	public static final int CID_IsDeviceOpened = 0;
    public static final int CID_OnDataReceived = 1;
	
	public static final int ACT_EnumerateAllDevices = 0;
    public static final int ACT_OpenDevice = 1;
	public static final int ACT_CloseDevice = 2;
	public static final int ACT_SendReport = 3;
	public static final int ACT_SendReportWithId = 4;
	
	public static final int EXP_DeviceCount = 0;
    public static final int EXP_InputBuffer = 1;
	public static final int EXP_ManufacturerName = 2;
	public static final int EXP_ProductName = 3;
	public static final int EXP_SerialNumber = 4;
	public static final int EXP_ProductId = 5;
    public static final int EXP_VendorId = 6;
	public static final int EXP_LastError = 7;
	public static final int EXP_OutputBufferLength = 8;
	
	private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
	
	public UsbDevice findDevice(int deviceId)
	{
		Iterator<UsbDevice> deviceIterator = _deviceList.values().iterator();
		UsbDevice device = null;
		int index=1;
		// Iterate all the available devices and find the device at defined id.
		while(deviceIterator.hasNext() && index!=deviceId)
		{
			index++;
			device = deviceIterator.next();
		}
		return device;
	}
	
    @Override
	public int getNumberOfConditions()
    {
        return 2;
    }
	
	@Override
	public boolean createRunObject(CBinaryFile file, CCreateObjectInfo cob, int version)
    {
		_curId = 0;
		_receivedQueue = new LinkedList<byte[]>();
		_lastError = "";

        return true;
    }
	
	
	@Override
	public void destroyRunObject(boolean bFast)
    {
    }
	
	@Override
	public int handleRunObject()
    {
        return 0;
    }

    @Override
	public void pauseRunObject()
    {
    }

    @Override
	public void continueRunObject()
    {
    }
	

	// Conditions
    // --------------------------------------------------
    @Override
	public boolean condition(int num, CCndExtension cnd)
    {
        switch (num)
        {
            case CID_IsDeviceOpened:
                return (_curId!=0);
            case CID_OnDataReceived:
                return IsThereAnyReceivedData();
        }
        return false;
    }

    // Actions
    // -------------------------------------------------
    @Override
	public void action(int num, CActExtension act)
    {
		String report = "";
		int formatType=0;
		int reportId=0;
		int deviceId = 0;
		try 
		{
			switch (num)
			{
				case ACT_EnumerateAllDevices:
					EnumerateAllDevices();
				case ACT_OpenDevice:
					deviceId = act.getParamExpression(rh, 0);
					OpenDevice(deviceId);
				case ACT_CloseDevice:
					CloseDevice();
				case ACT_SendReport:
					report = act.getParamExpString(rh, 0);
					formatType = act.getParamExpression(rh, 1);
					SendReport(report, formatType, 0);
				case ACT_SendReportWithId:
					report = act.getParamExpString(rh, 0);
					formatType = act.getParamExpression(rh, 1);
					reportId = act.getParamExpression(rh, 2);
					SendReport(report, formatType, reportId);
			}
		}
		catch (Exception e) {
			Log.Log("exception caught by action:" + e.toString());
			_lastError = e.toString();
		}
    }

    // Expressions
    // --------------------------------------------
    @Override
	public CValue expression(int num)
    {
		int deviceId =0;
		switch (num)
        {
            case EXP_DeviceCount:
                if(_deviceList!=null)
				{
					return new CValue(_deviceList.size());
				}
				else
				{
					return new CValue(0);
				}
            case EXP_InputBuffer:
				byte[] buf  = GetReceivedDataFromQueue();
				int formatType = ho.getExpParam().getInt();
				String s = "";
				if(formatType==0)//hexadecimal
				{
					char[] hexChars = new char[buf.length * 2];
					for ( int j = 0; j < buf.length; j++ ) {
						int v = buf[j] & 0xFF;
						hexChars[j * 2] = hexArray[v >>> 4];
						hexChars[j * 2 + 1] = hexArray[v & 0x0F];
					}
					s = new String(hexChars);
				}
				else if(formatType==1)//ascii
				{
					s = new String(buf);
				}
				return new CValue(s);
			case EXP_ManufacturerName:
				deviceId = ho.getExpParam().getInt();
				return new CValue (findDevice(deviceId).getManufacturerName());
			case EXP_ProductName:
				deviceId = ho.getExpParam().getInt();
				return new CValue(findDevice(deviceId).getProductName());
			case EXP_SerialNumber:
				deviceId = ho.getExpParam().getInt();
				return new CValue(findDevice(deviceId).getSerialNumber());
			case EXP_ProductId:
				deviceId = ho.getExpParam().getInt();
				return new CValue(findDevice(deviceId).getProductId());
			case EXP_VendorId:
				deviceId = ho.getExpParam().getInt();
				return new CValue(findDevice(deviceId).getVendorId());
			case EXP_LastError:
				return new CValue(_lastError);
			case EXP_OutputBufferLength:
				if(_usbDevice!=null)
				{
					return new CValue(_usbDevice.getInterface(0).getEndpoint(1).getMaxPacketSize());
				}
				else
				{
					return new CValue(0);
				}
        }
		
		return new CValue(0);
    }
	
	private void EnumerateAllDevices()
	{
		_usbManager = (UsbManager) MMFRuntime.inst.getSystemService(Context.USB_SERVICE);
		_deviceList = _usbManager.getDeviceList();
	}
	
	
	private static byte[] hexStringToByteArray(String s) 
	{
		int len = s.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
								 + Character.digit(s.charAt(i+1), 16));
		}
		return data;
	}
	
	private void SendReport(String report, int formatType, int reportId)
	{
		if(_curId!=0)
		{
			if(formatType==0)//hexadecimal
			{
				int len = report.length();
				byte[] b = hexStringToByteArray(report);
				byte[] outputbuf = new byte[len + 1];
				outputbuf[0]=(byte)reportId;
				for (int i = 0; i < len; i ++) 
				{
					outputbuf[i+1] = b[i];
				}
				WriteData(outputbuf);
			}
			else if(formatType==1)//ascii
			{
				int len = report.length();
				byte[] b = report.getBytes(Charset.forName("UTF-8"));
				byte[] outputbuf = new byte[len + 1];
				outputbuf[0]=(byte)reportId;
				for (int i = 0; i < len; i ++) 
				{
					outputbuf[i+1] = b[i];
				}
				WriteData(outputbuf);
			}
			else
			{
						
			}
		}
	}
	
	/**
	 * Searches for the device and opens it if successful
	 * @return true, if connection was successful
	 */
	private void OpenDevice(int deviceId)
	{
		// if already connected
		if(_usbDevice!=null && deviceId == _curId)
		{
			return;
		}
		else
		{
			_usbManager = (UsbManager) MMFRuntime.inst.getSystemService(Context.USB_SERVICE);
		
			Iterator<UsbDevice> deviceIterator = _deviceList.values().iterator();
			_usbDevice = null;
			UsbDevice device = null;
			
			int index=1;
			// Iterate all the available devices and find the device at defined id.
			while(deviceIterator.hasNext() && index!=deviceId)
			{
				index++;
				device = deviceIterator.next();
			}
			
			_usbDevice = device;
			_deviceName = _usbDevice.getDeviceName();
						
			if (_usbDevice == null) {
				_lastError = "Cannot find the device. Did you forgot to plug it?";
				_curId=0;
				return;
			}
			_curId = deviceId;
			
			// Create an intent and request a permission.
			PendingIntent mPermissionIntent = PendingIntent.getBroadcast(MMFRuntime.inst, 0, new Intent(ACTION_USB_PERMISSION), 0);
			IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
			MMFRuntime.inst.registerReceiver(mUsbReceiver, filter);
	 
			_usbManager.requestPermission(_usbDevice, mPermissionIntent);
			StartReadingThread();
		}
	}
	
	/**
	 * Closes the reading thread of the device.
	 */
	private void CloseDevice() {
		StopReadingThread();
		_usbDevice = null;
		_curId = 0;
	}
	
	/**
	 * Starts the thread that continuously reads the data from the device. 
	 * Should be called in order to be able to talk with the device.
	 */
	private void StartReadingThread() 
	{
		if (_readingThread == null) {
			_readingThread = new Thread(readerReceiver);
			_readingThread.start();
		} else {
			_lastError = "Reading thread already started";
		}
	}
	
	/**
	 * Stops the thread that continuously reads the data from the device.
	 * If it is stopped - talking to the device would be impossible.
	 */
	@SuppressWarnings("deprecation")
	private void StopReadingThread() {
		if (_readingThread != null) {
			// Just kill the thread. It is better to do that fast if we need that asap.
			_readingThread.stop();
			_readingThread = null;
		} else {
			_lastError = "No reading thread to stop";
		}
	}
	
	/**
	 * Write data to the usb hid. Data is written as-is, so calling method is responsible for adding header data.
	 * @param bytes is the data to be written.
	 * @return true if succeed.
	 */
	private boolean WriteData(byte[] bytes) {
		
			// Lock that is common for read/write methods.
			synchronized (_locker) {
				UsbInterface writeIntf = _usbDevice.getInterface(0);
				UsbEndpoint writeEp = writeIntf.getEndpoint(1);
				UsbDeviceConnection writeConnection = _usbManager.openDevice(_usbDevice); 
				
				// Lock the usb interface.
				writeConnection.claimInterface(writeIntf, true);
				
				// Write the data as a bulk transfer with defined data length.
				int r = writeConnection.bulkTransfer(writeEp, bytes, bytes.length, 0);
				if (r == -1) 
				{
					_lastError = "Error happened while writing data. No ACK";
				}
 
				// Release the usb interface.
				writeConnection.releaseInterface(writeIntf);
				writeConnection.close();
			}
			
		return true;
	}
	
	/**
	 * @return true if there are any data in the queue to be read.
	 */
	private boolean IsThereAnyReceivedData() {
		synchronized(_locker) {
			return !_receivedQueue.isEmpty();
		}
	}
	
	/**
	 * Queue the data from the read queue.
	 * @return queued data.
	 */
	private byte[] GetReceivedDataFromQueue() {
		synchronized(_locker) {
			return _receivedQueue.poll();
		}
	}
	
	// The thread that continuously receives data from the device and put it to the queue.
	private Runnable readerReceiver = new Runnable() {
	    public void run() {
	    	if (_usbDevice == null) {
				_lastError = "No device to read from";
	    		return;
	    	}
 
	    	UsbEndpoint readEp;
	    	UsbDeviceConnection readConnection = null;
	    	UsbInterface readIntf = null;
	    	boolean readerStartedMsgWasShown = false;
	    	
	    	// We will continuously ask for the data from the device and store it in the queue.
			while (true) {
				// Lock that is common for read/write methods.
				synchronized (_locker) {
					try
					{
						if (_usbDevice == null) {
							OpenDevice(_curId);
							_lastError = "No device";
							
							Sleep(10000);
							continue;
						}
						
				    	readIntf = _usbDevice.getInterface(0);
						readEp = readIntf.getEndpoint(0);
						if (!_usbManager.getDeviceList().containsKey(_deviceName)) {
							_lastError = "Failed to connect to the device. Retrying to acquire it.";
							OpenDevice(_curId);
							if (!_usbManager.getDeviceList().containsKey(_deviceName)) {
								_lastError = "No device";
								
								Sleep(10000);
								continue;
							}
						}
						
						try
						{
							
							readConnection = _usbManager.openDevice(_usbDevice); 
							
							if (readConnection == null) {
								_lastError = "Cannot start reader because the user didn't give me permissions or the device is not present. Retrying in 2 sec...";
								Sleep(2000);
								continue;
							}
							
							// Claim and lock the interface in the android system.
							readConnection.claimInterface(readIntf, true);
						}
						catch (SecurityException e) {
							_lastError = "Cannot start reader because the user didn't give me permissions or the device is not present. Retrying in 2 sec...";
							
							Sleep(2000);
							continue;
						}
						
						// Show the reader started message once.
						if (!readerStartedMsgWasShown) {
							readerStartedMsgWasShown = true;
						}
						
						// Read the data as a bulk transfer with the size = MaxPacketSize
						int packetSize = readEp.getMaxPacketSize();
						byte[] bytes = new byte[packetSize];
						int r = readConnection.bulkTransfer(readEp, bytes, packetSize, 50);
						if (r >= 0) {
							byte[] truncatedBytes = new byte[r]; // Truncate bytes in the honor of r
							
							int i=0; 
							for (byte b : bytes) {
								truncatedBytes[i] = b;
								i++;
							}
							
							_receivedQueue.add(truncatedBytes); // Store received data
						}
						
						// Release the interface lock.
						readConnection.releaseInterface(readIntf);
						readConnection.close();
						} 
					
					catch (ThreadDeath e) {
						if (readConnection != null) {
							readConnection.releaseInterface(readIntf);
							readConnection.close();
						}
						
						throw e;
					}
				}
				
				// Sleep for 10 ms to pause, so other thread can write data or anything. 
				// As both read and write data methods lock each other - they cannot be run in parallel.
				// Looks like Android is not so smart in planning the threads, so we need to give it a small time
				// to switch the thread context.
				Sleep(10);
			}
	    }
	};
	
	private void Sleep(int milliseconds) {
		try {
			Thread.sleep(milliseconds);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
 
	    public void onReceive(Context context, Intent intent) {
	        String action = intent.getAction();
	        if (ACTION_USB_PERMISSION.equals(action)) {
	            synchronized (this) {
	                UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
 
	                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
	                    if(device != null){
	                      //call method to set up device communication
	                   }
	                } 
	                else {
						_lastError = "Permission denied for the device " + device;
	                }
	            }
	        }
	    }
	};
}