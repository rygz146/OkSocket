package com.xuhao.android.libsocket.impl.blockio.io;

import com.xuhao.android.libsocket.impl.abilities.IReader;
import com.xuhao.android.libsocket.impl.exceptions.ReadException;
import com.xuhao.android.libsocket.sdk.OkSocketOptions;
import com.xuhao.android.libsocket.sdk.bean.IHeaderProtocol;
import com.xuhao.android.libsocket.sdk.bean.OriginalData;
import com.xuhao.android.libsocket.sdk.connection.abilities.IStateSender;
import com.xuhao.android.libsocket.sdk.connection.interfacies.IAction;
import com.xuhao.android.libsocket.utils.BytesUtils;
import com.xuhao.android.libsocket.utils.SL;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Created by xuhao on 2017/5/31.
 */

public class Reader implements IReader {

    private OkSocketOptions mOkOptions;

    private IStateSender mStateSender;

    private InputStream mInputStream;

    private ByteBuffer mRemainingBuf;

    public Reader(InputStream inputStream, IStateSender stateSender) {
        mStateSender = stateSender;
        mInputStream = inputStream;
    }

    @Override
    public void read() throws RuntimeException {
        OriginalData originalData = new OriginalData();
        IHeaderProtocol headerProtocol = mOkOptions.getHeaderProtocol();
        ByteBuffer headBuf = ByteBuffer.allocate(headerProtocol.getHeaderLength());
        headBuf.order(mOkOptions.getReadByteOrder());
        try {
            if (mRemainingBuf != null) {
                mRemainingBuf.flip();
                int length = Math.min(mRemainingBuf.remaining(), headerProtocol.getHeaderLength());
                headBuf.put(mRemainingBuf.array(), 0, length);
                if (length < headerProtocol.getHeaderLength()) {
                    //there are no data left
                    mRemainingBuf = null;
                    for (int i = 0; i < headerProtocol.getHeaderLength() - length; i++) {
                        headBuf.put((byte) mInputStream.read());
                    }
                } else {
                    mRemainingBuf.position(headerProtocol.getHeaderLength());
                }
            } else {
                for (int i = 0; i < headBuf.capacity(); i++) {
                    headBuf.put((byte) mInputStream.read());
                }
            }
            originalData.setHeadBytes(headBuf.array());
            SL.i("read head: " + BytesUtils.toHexStringForLog(headBuf.array()));
            int bodyLength = headerProtocol.getBodyLength(originalData.getHeadBytes(), mOkOptions.getReadByteOrder());
            SL.i("need read body length: " + bodyLength);
            if (bodyLength > 0) {
                if (bodyLength > mOkOptions.getMaxReadDataMB() * 1024 * 1024) {//大于最大的读取容量,说明数据有问题
                    throw new ReadException("we can't read data bigger than " + mOkOptions.getMaxReadDataMB() + "Mb");
                }
                ByteBuffer byteBuffer = ByteBuffer.allocate(bodyLength);
                byteBuffer.order(mOkOptions.getReadByteOrder());
                if (mRemainingBuf != null) {
                    int bodyStartPosition = mRemainingBuf.position();
                    int length = Math.min(mRemainingBuf.remaining(), bodyLength);
                    byteBuffer.put(mRemainingBuf.array(), bodyStartPosition, length);
                    mRemainingBuf.position(bodyStartPosition + length);
                    if (length == bodyLength) {
                        if (mRemainingBuf.remaining() > 0) {//there are data left
                            mRemainingBuf = ByteBuffer.allocate(mRemainingBuf.remaining());
                            mRemainingBuf.order(mOkOptions.getReadByteOrder());
                            mRemainingBuf
                                    .put(mRemainingBuf.array(), mRemainingBuf.position(), mRemainingBuf.remaining());
                        } else {//there are no data left
                            mRemainingBuf = null;
                        }
                        //cause this time data from remaining buffer not from channel.
                        originalData.setBodyBytes(byteBuffer.array());
                        mStateSender.sendBroadcast(IAction.ACTION_READ_COMPLETE, originalData);
                        return;
                    } else {//there are no data left in buffer and some data pieces in channel
                        mRemainingBuf = null;
                    }
                }
                readBodyFromChannel(byteBuffer);
                originalData.setBodyBytes(byteBuffer.array());
            } else if (bodyLength == 0) {
                originalData.setBodyBytes(new byte[0]);
            } else if (bodyLength < 0) {
                throw new ReadException(
                        "this socket input stream has some problem,wrong body length " + bodyLength
                                + ",we'll disconnect");
            }
            mStateSender.sendBroadcast(IAction.ACTION_READ_COMPLETE, originalData);
        } catch (Exception e) {
            ReadException readException = new ReadException(e);
            throw readException;
        }
    }

    private void readBodyFromChannel(ByteBuffer byteBuffer) throws IOException {
        while (byteBuffer.hasRemaining()) {
            try {
                byte[] bufArray = new byte[mOkOptions.getReadSingleTimeBufferBytes()];
                int len = mInputStream.read(bufArray);
                if (len < 0) {
                    break;
                }
                int remaining = byteBuffer.remaining();
                if (len > remaining) {
                    byteBuffer.put(bufArray, 0, remaining);
                    mRemainingBuf = ByteBuffer.allocate(len - remaining);
                    mRemainingBuf.order(mOkOptions.getReadByteOrder());
                    mRemainingBuf.put(bufArray, remaining, len - remaining);
                } else {
                    byteBuffer.put(bufArray, 0, len);
                }
            } catch (Exception e) {
                throw e;
            }
        }
        SL.i("read total bytes: " + BytesUtils.toHexStringForLog(byteBuffer.array()));
        SL.i("read total length:" + (byteBuffer.capacity() - byteBuffer.remaining()));
    }

    @Override
    public void setOption(OkSocketOptions option) {
        mOkOptions = option;
    }
}
