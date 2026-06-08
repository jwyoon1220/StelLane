import sys

f = r'c:\Users\me\IdeaProject\StelLane\engine\src\main\kotlin\io\github\jwyoon1220\engine\VideoBackground.kt'

with open(f, 'r', encoding='utf-8') as file:
    text = file.read()

# 1. Add hwaccel=auto
old1 = '''        var grabber: FFmpegFrameGrabber? = null
        try {
            grabber = FFmpegFrameGrabber(path)
            // 비디오 RGBA, 오디오 Stereo S16 44.1kHz 강제 지정'''
new1 = '''        var grabber: FFmpegFrameGrabber? = null
        try {
            grabber = FFmpegFrameGrabber(path)
            grabber.setVideoOption("hwaccel", "auto")
            // 비디오 RGBA, 오디오 Stereo S16 44.1kHz 강제 지정'''

if old1 not in text:
    print("Failed to find block 1")
    sys.exit(1)
text = text.replace(old1, new1)

# 2. Remove OpenAL init from player thread
old2 = '''        try {
            // 백그라운드 스레드에 OpenAL 컨텍스트 바인딩
            alcMakeContextCurrent(alcContext)
            val deviceCaps = ALC.createCapabilities(alcDevice)
            AL.createCapabilities(deviceCaps)

            var lastCurrentSample = -1L'''
new2 = '''        try {
            var lastCurrentSample = -1L'''

if old2 not in text:
    print("Failed to find block 2")
    sys.exit(1)
text = text.replace(old2, new2)

# 3. Remove context reset from finally
old3 = '''        } finally {
            runCatching { alSourceStop(alSource) }
            runCatching { alcMakeContextCurrent(0L) }
        }'''
new3 = '''        } finally {
            runCatching { alSourceStop(alSource) }
        }'''

if old3 not in text:
    print("Failed to find block 3")
    sys.exit(1)
text = text.replace(old3, new3)

with open(f, 'w', encoding='utf-8') as file:
    file.write(text)

print("Patched successfully!")
