package com.ob.screen.activity;

import android.graphics.PixelFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.ob.screen.R;
import com.ob.screen.util.TyteUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Created by pengds on 2017/9/2.
 *
 */

public class ScreenReceiveActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "ScreenReceiveActivity";
    int width = 1280;
    int height = 720;
    int framerate = 20;
    private int mCount = 0;
    private MediaCodec mediaCodec;
    private SurfaceView surface_view;
    private SurfaceHolder mSurfaceHolder;
    private DatagramSocket mDatagramSocket;
    private boolean getFrameData = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //去掉状态栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
//        // 保持屏幕常亮
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_screen_receive);
        surface_view = (SurfaceView) findViewById(R.id.screen_share_re_surface_view);
        mSurfaceHolder = surface_view.getHolder();
        mSurfaceHolder.setFormat(PixelFormat.RGBX_8888);//优化花屏
        //    android:theme="@android:style/Theme.Translucent.NoTitleBar.Fullscreen" // 花屏可以考虑设置这个样式的acticity，不过尝试了效果有限~
        mSurfaceHolder.addCallback(this);

        initUdp();
    }
    private static final int FRAME_MAX_NUMBER = 40964;
    private byte[] frameBytes = new byte[FRAME_MAX_NUMBER];
    private void initUdp() {
        try {
            mDatagramSocket = new DatagramSocket(2333);
            final DatagramPacket dp = new DatagramPacket(frameBytes, frameBytes.length);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (getFrameData){
                        try {
                            mDatagramSocket.receive(dp);
                        } catch (IOException e) {
                            Log.e(TAG, "IOException: "+e.toString());
                            e.printStackTrace();
                        }
                        byte[] data = dp.getData();
//                        Log.e(TAG, data.length+ " run: "+ Arrays.toString(data));
                        byte[] lengthByte = new byte[4];
                        System.arraycopy(data, 0, lengthByte, 0, 4);
                        int frameLenth = TyteUtil.byteArrayToInt(lengthByte);
                        Log.e(TAG, frameLenth+ " 接收的长度: ");
                        if(frameLenth == 0) continue;
                        frameLenth = 40960 < frameLenth ? 40960 : frameLenth;
                         byte[] frame = new byte[frameLenth];
                        System.arraycopy(data, 4, frame, 0, frameLenth);
                        Log.e(TAG, frameLenth+ " 喂的数据: "+ Arrays.toString(frame));
                        onFrame(frame);
                    }
                    Log.e(TAG, "....:结束");
                    mDatagramSocket.close();
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Exception:"+e.toString());
            e.printStackTrace();
        }
    }

    public void onFrame(byte[] buf) {
        if (buf == null)
            return;
        int length = buf.length;
        ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();//拿到输入缓冲区,用于传送数据进行编码
        //返回一个填充了有效数据的input buffer的索引，如果没有可用的buffer则返回-1.当timeoutUs==0时，该方法立即返回；当timeoutUs<0时，无限期地等待一个可用的input buffer;当timeoutUs>0时，至多等待timeoutUs微妙
//        int inputBufferIndex = mediaCodec.dequeueInputBuffer(1);// =>0时,至多等待x微妙   如果发送源快速滑动比如放视频, 花屏明显.. ...
       int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);//    <-1时,无限期地等待一个可用的input buffer  会出现:一直等待导致加载异常, 甚至会吃掉网络通道, 没有任何异常出现...(调试中大部分是因为sps和pps没有写入到解码器, 保证图像信息的参数写入解码器很重要)
        if (inputBufferIndex >= 0) {//当输入缓冲区有效时,就是>=0
            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.put(buf, 0, length);//往输入缓冲区写入数据,关键点
//            int value = buf[4] & 0x0f;//nalu, 5是I帧, 7是sps 8是pps.
//            if (value == 7)//如果不能保证第一帧写入的是sps和pps， 可以用这种方式等待sps和pps发送到之后写入解码器
//                mediaCodec.queueInputBuffer(inputBufferIndex, 0, length, mCount * 30, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);//更新sps和pps
//            else
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, length, mCount * 30, 0);//将缓冲区入队
            mCount++;//用于queueInputBuffer presentationTimeUs 此缓冲区的显示时间戳（以微秒为单位），通常是这个缓冲区应该呈现的媒体时间
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);//拿到输出缓冲区的索引
//        Log.e(TAG, "outputBufferIndex" + outputBufferIndex);
        while (outputBufferIndex >= 0) {
            mediaCodec.releaseOutputBuffer(outputBufferIndex, true);//显示并释放资源
            outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);//再次获取数据，如果没有数据输出则outIndex=-1 循环结束
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.e(TAG, "surfaceCreated");
        getFrameData = true;
        creatMediaCodec(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        getFrameData = false;
        Log.e(TAG, "surfaceDestroyed");
        if(mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec = null;
        }
    }

    void creatMediaCodec(SurfaceHolder holder) {
        try {
            //通过多媒体格式名创建一个可用的解码器
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
        } catch (IOException e) {
            Log.e(TAG, "通过多媒体格式名创建一个可用的解码器" + e.toString());
            e.printStackTrace();
        }
        //初始化编码器
        final MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);
        //这里可以尝试写死SPS和PPS，部分机型上的解码器可以正常工作。
//        byte[] header_sps = {0, 0, 0, 1, 103, 66, 0, 42, (byte) 149, (byte) 168, 30, 0, (byte) 137, (byte) 249, 102, (byte) 224, 32, 32, 32, 64};
//        byte[] header_pps = {0, 0, 0, 1, 104, (byte) 206, 60, (byte) 128, 0, 0, 0, 1, 6, (byte) 229, 1, (byte) 151, (byte) 128};
//        mediaformat.setByteBuffer("csd-0", ByteBuffer.wrap(header_sps));
//        mediaformat.setByteBuffer("csd-1", ByteBuffer.wrap(header_pps));
        mediaformat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
        //指定解码后的帧格式
//            mediaformat.setInteger(MediaFormat.KEY_FRAME_RATE, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);//解码器将编码的帧解码为这种指定的格式,YUV420Flexible是几乎所有解码器都支持的

        //设置配置参数，参数介绍 ：
        // format   如果为解码器，此处表示输入数据的格式；如果为编码器，此处表示输出数据的格式。
        //surface   指定一个surface，可用作decode的输出渲染。
        //crypto    如果需要给媒体数据加密，此处指定一个crypto类.
        //   flags  如果正在配置的对象是用作编码器，此处加上CONFIGURE_FLAG_ENCODE 标签。
        mediaCodec.configure(mediaformat, holder.getSurface(), null, 0);
        mediaCodec.start();
        Log.e(TAG, "创建解码器");
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        mSurfaceHolder.getSurface().release();
        mSurfaceHolder = null;
        surface_view = null;
        mDatagramSocket.close();
        super.onDestroy();
        System.exit(0);
    }
}
//如果h264中无pps及sps数据，则手动填入
//                    byte[] header_sps = {0, 0, 0, 1, 103, 66, 0, 42, (byte) 149, (byte) 168, 30, 0, (byte) 137, (byte) 249, 102, (byte) 224, 32, 32, 32, 64};
//                    byte[] header_pps = {0, 0, 0, 1, 104, (byte) 206, 60, (byte) 128, 0, 0, 0, 1, 6, (byte) 229, 1, (byte) 151, (byte) 128};
//                    mediaformat.setByteBuffer("csd-0", ByteBuffer.wrap(header_sps));
//                    mediaformat.setByteBuffer("csd-1", ByteBuffer.wrap(header_pps));
//          指定解码后的帧格式
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);//解码器将编码的帧解码为这种指定的格式,YUV420Flexible是几乎所有解码器都支持的
//        }
//        //设置码率，通常码率越高，视频越清晰，但是对应的视频也越大，这个值我默认设置成了2000000，也就是通常所说的2M，这已经不低了，如果你不想录制这么清晰的，你可以设置成500000，也就是500k
//        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
//        //设置帧率
//        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
//        //COLOR_FormatSurface这里表明数据将是一个graphicbuffer元数据
//        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
//        //IFRAME_INTERVAL是指的帧间隔，这是个很有意思的值，它指的是，关键帧的间隔时间。通常情况下，你设置成多少问题都不大。
//        //比如你设置成10，那就是10秒一个关键帧。但是，如果你有需求要做视频的预览，那你最好设置成1
//        //因为如果你设置成10，那你会发现，10秒内的预览都是一个截图
//        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);//这个参数在很多手机上无效，特别是6.0以上的