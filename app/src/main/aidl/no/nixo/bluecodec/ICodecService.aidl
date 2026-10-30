package no.nixo.bluecodec;
interface ICodecService {
    String inspect() = 0;
    String apply(String address, int codec) = 1;
    String audioPolicies() = 2;
    String setAudioPolicy(String address, int profile, int policy) = 3;
    String connectAudio(String address) = 4;
    String turnBluetoothOff() = 5;
    String turnBluetoothOn() = 6;
    String manualConnectionRequests(boolean enabled) = 7;
    String selectSoundOutput(String target) = 8;
    void destroy() = 16777114;
}
