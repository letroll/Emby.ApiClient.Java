package mediabrowser.apiinteraction.android.profiles;

import android.media.MediaCodecInfo;
import android.util.Range;
import mediabrowser.model.dlna.*;
import mediabrowser.model.extensions.StringHelper;

import java.util.ArrayList;

public class Api21Builder extends Api16Builder{

    public Api21Builder(AndroidProfileOptions defaults) {
        super(defaults);
    }

    @Override
    protected void processCodecProfile(MediaCodecInfo codecInfo, String type, MediaCodecInfo.CodecCapabilities codecCapabilities, CodecProfile profile){

        super.processCodecProfile(codecInfo, type, codecCapabilities, profile);

        if (profile.getType() == CodecType.Audio){
            addAudioCapabilities(codecCapabilities, profile);
        }
        else if (profile.getType() == CodecType.VideoAudio){
            addAudioCapabilities(codecCapabilities, profile);
        }
        else if (profile.getType() == CodecType.Video){
            addVideoCapabilities(codecCapabilities, profile);
        }
    }

    private void addVideoCapabilities(MediaCodecInfo.CodecCapabilities codecCapabilities, CodecProfile profile){

        MediaCodecInfo.VideoCapabilities videoCaps = codecCapabilities.getVideoCapabilities();

        ArrayList<ProfileCondition> conditions = new ArrayList<ProfileCondition>();

        conditions.add(new ProfileCondition(ProfileConditionType.NotEquals, ProfileConditionValue.IsAnamorphic, "true"));

        if (StringHelper.IndexOfIgnoreCase(profile.getCodec(), "h264") != -1){

            conditions.add(new ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.VideoBitDepth, "8"));
        }

        // Video max bitrate
        Range<Integer> bitrateRange = videoCaps.getBitrateRange();
        String maxBitrate = String.valueOf(bitrateRange.getUpper());
        conditions.add(new ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.VideoBitrate, maxBitrate));

        // Video min bitrate
        String minBitrate = String.valueOf(bitrateRange.getLower());
        conditions.add(new ProfileCondition(ProfileConditionType.GreaterThanEqual, ProfileConditionValue.VideoBitrate, minBitrate));

        // Video max height
        Range<Integer> heightRange = videoCaps.getSupportedHeights();
        String maxHeight = String.valueOf(heightRange.getUpper());
        //conditions.add(new ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.Height, maxHeight));

        // Video min height
        String minHeight = String.valueOf(heightRange.getLower());
        conditions.add(new ProfileCondition(ProfileConditionType.GreaterThanEqual, ProfileConditionValue.Height, minHeight));

        // Video max width
        Range<Integer> widthRange = videoCaps.getSupportedHeights();
        conditions.add(new ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.Width, String.valueOf(widthRange.getUpper())));

        // Video min width
        conditions.add(new ProfileCondition(ProfileConditionType.GreaterThanEqual, ProfileConditionValue.Width, String.valueOf(widthRange.getLower())));

        profile.setConditions(conditions.toArray(new ProfileCondition[conditions.size()]));

        AddProfileLevels(codecCapabilities, profile);
    }

    private void addAudioCapabilities(MediaCodecInfo.CodecCapabilities codecCapabilities, CodecProfile profile){

        MediaCodecInfo.AudioCapabilities audioCaps = codecCapabilities.getAudioCapabilities();

        ArrayList<ProfileCondition> conditions = new ArrayList<ProfileCondition>();

        // Audio channels
        int maxAudioChannels = audioCaps.getMaxInputChannelCount();

        String channels = String.valueOf(maxAudioChannels);
        conditions.add(new ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.AudioChannels, channels));

        // Audio sample rate
        // TODO: Add this later. There currently is no profile condition support for it

        // Audio max bitrate
        Range<Integer> bitrateRange = audioCaps.getBitrateRange();
        String maxBitrate = String.valueOf(bitrateRange.getUpper());
        conditions.add(new ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.AudioBitrate, maxBitrate));

        // Audio min bitrate
        String minBitrate = String.valueOf(bitrateRange.getLower());
        conditions.add(new ProfileCondition(ProfileConditionType.GreaterThanEqual, ProfileConditionValue.AudioBitrate, minBitrate));

        profile.setConditions(conditions.toArray(new ProfileCondition[conditions.size()]));
    }
}
