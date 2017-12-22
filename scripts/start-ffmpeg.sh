#!/bin/bash

PID_DIR=/var/run/jibri/

#url is passed as the second parameter from launch_recording.sh
#url pattern : https://video2.campcite.com/myRoomName#config.iAmRecorder=true&config.externalConnectUrl=null&config.hosts.domain=recorder.video2.campcite.com
STREAM=/tmp/$(echo $2 | sed 's/#.*//' | sed 's#.*/##').flv

if [[ $STREAM == rtmp://* ]]; then
  FORMAT='flv'
else
  FORMAT='mp4'
fi

: ${RESOLUTION:=1280x720}
: ${FRAMERATE:=30}
: ${PRESET:=veryfast}
: ${QUEUE_SIZE:=4096}

#use alsa directly
: ${INPUT_DEVICE:='hw:0,1,0'}
: ${MAX_BITRATE:='2976'}
: ${BUFSIZE:=$(($MAX_BITRATE * 2))}
: ${CRF:=25}
: ${G:=$(($FRAMERATE * 2))}
#use pulse for audio input
#INPUT_DEVICE='pulse'

DISPLAY=:0

if [[ $STREAM == rtmp://* ]]; then
  FORMAT='flv'
  STREAM_OPTIONS="-maxrate ${MAX_BITRATE}k -bufsize ${BUFSIZE}k"
else
  STREAM_OPTIONS="-profile:v main -level 3.1"
  FORMAT='mp4'
fi

#Record the output of display :0 plus the ALSA loopback device hw:0,1,0
ffmpeg -y -v info -f x11grab -draw_mouse 0 -r $FRAMERATE -s $RESOLUTION -thread_queue_size $QUEUE_SIZE -i ${DISPLAY}.0+0,0 \
    -f alsa -thread_queue_size $QUEUE_SIZE -i $INPUT_DEVICE  -acodec aac -strict -2 -ar 44100 \
    -c:v libx264 -preset $PRESET $STREAM_OPTIONS -pix_fmt yuv420p -r $FRAMERATE -crf $CRF -g $G  -tune zerolatency \
    -f $FORMAT $STREAM > /tmp/jibri-ffmpeg.out 2>&1 &
echo $! > $PID_DIR/ffmpeg.pid
