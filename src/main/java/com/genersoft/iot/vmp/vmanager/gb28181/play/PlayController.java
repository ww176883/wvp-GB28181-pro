package com.genersoft.iot.vmp.vmanager.gb28181.play;

import com.alibaba.fastjson.JSONArray;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.conf.exception.SsrcTransactionNotFoundException;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.session.VideoStreamSessionManager;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.genersoft.iot.vmp.vmanager.gb28181.play.bean.PlayResult;
import com.genersoft.iot.vmp.service.IMediaService;
import com.genersoft.iot.vmp.service.IPlayService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import org.springframework.web.context.request.async.DeferredResult;

import javax.sip.InvalidArgumentException;
import javax.sip.SipException;
import java.text.ParseException;
import java.util.List;
import java.util.UUID;

@Tag(name  = "国标设备点播")
@CrossOrigin
@RestController
@RequestMapping("/api/play")
public class PlayController {

	private final static Logger logger = LoggerFactory.getLogger(PlayController.class);

	@Autowired
	private SIPCommander cmder;

	@Autowired
	private VideoStreamSessionManager streamSession;

	@Autowired
	private IVideoManagerStorage storager;

	@Autowired
	private IRedisCatchStorage redisCatchStorage;

	@Autowired
	private ZLMRESTfulUtils zlmresTfulUtils;

	@Autowired
	private DeferredResultHolder resultHolder;

	@Autowired
	private IPlayService playService;

	@Autowired
	private IMediaService mediaService;

	@Autowired
	private IMediaServerService mediaServerService;

	@Operation(summary = "开始点播")
	@Parameter(name = "deviceId", description = "设备国标编号", required = true)
	@Parameter(name = "channelId", description = "通道国标编号", required = true)
	@GetMapping("/start/{deviceId}/{channelId}")
	public DeferredResult<WVPResult<StreamInfo>> play(@PathVariable String deviceId,
													   @PathVariable String channelId) {

		// 获取可用的zlm
		Device device = storager.queryVideoDevice(deviceId);
		MediaServerItem newMediaServerItem = playService.getNewMediaServerItem(device);
		PlayResult playResult = playService.play(newMediaServerItem, deviceId, channelId, null, null, null);

		return playResult.getResult();
	}


	@Operation(summary = "停止点播")
	@Parameter(name = "deviceId", description = "设备国标编号", required = true)
	@Parameter(name = "channelId", description = "通道国标编号", required = true)
	@GetMapping("/stop/{deviceId}/{channelId}")
	public JSONObject playStop(@PathVariable String deviceId, @PathVariable String channelId) {

		logger.debug(String.format("设备预览/回放停止API调用，streamId：%s_%s", deviceId, channelId ));

		if (deviceId == null || channelId == null) {
			throw new ControllerException(ErrorCode.ERROR400);
		}

		Device device = storager.queryVideoDevice(deviceId);
		if (device == null) {
			throw new ControllerException(ErrorCode.ERROR100.getCode(), "设备[" + deviceId + "]不存在");
		}

		StreamInfo streamInfo = redisCatchStorage.queryPlayByDevice(deviceId, channelId);
		if (streamInfo == null) {
			throw new ControllerException(ErrorCode.ERROR100.getCode(), "点播未找到");
		}

		try {
			logger.warn("[停止点播] {}/{}", device.getDeviceId(), channelId);
			cmder.streamByeCmd(device, channelId, streamInfo.getStream(), null, null);
		} catch (InvalidArgumentException | SipException | ParseException | SsrcTransactionNotFoundException e) {
			logger.error("[命令发送失败] 停止点播， 发送BYE: {}", e.getMessage());
			throw new ControllerException(ErrorCode.ERROR100.getCode(), "命令发送失败: " + e.getMessage());
		}
		redisCatchStorage.stopPlay(streamInfo);

		storager.stopPlay(streamInfo.getDeviceID(), streamInfo.getChannelId());
		JSONObject json = new JSONObject();
		json.put("deviceId", deviceId);
		json.put("channelId", channelId);
		return json;

	}

	/**
	 * 将不是h264的视频通过ffmpeg 转码为h264 + aac
	 * @param streamId 流ID
	 */
	@Operation(summary = "将不是h264的视频通过ffmpeg 转码为h264 + aac")
	@Parameter(name = "streamId", description = "视频流ID", required = true)
	@PostMapping("/convert/{streamId}")
	public JSONObject playConvert(@PathVariable String streamId) {
		StreamInfo streamInfo = redisCatchStorage.queryPlayByStreamId(streamId);
		if (streamInfo == null) {
			streamInfo = redisCatchStorage.queryPlayback(null, null, streamId, null);
		}
		if (streamInfo == null) {
			logger.warn("视频转码API调用失败！, 视频流已经停止!");
			throw new ControllerException(ErrorCode.ERROR100.getCode(), "未找到视频流信息, 视频流可能已经停止");
		}
		MediaServerItem mediaInfo = mediaServerService.getOne(streamInfo.getMediaServerId());
		JSONObject rtpInfo = zlmresTfulUtils.getRtpInfo(mediaInfo, streamId);
		if (!rtpInfo.getBoolean("exist")) {
			logger.warn("视频转码API调用失败！, 视频流已停止推流!");
			throw new ControllerException(ErrorCode.ERROR100.getCode(), "未找到视频流信息, 视频流可能已停止推流");
		} else {
			String dstUrl = String.format("rtmp://%s:%s/convert/%s", "127.0.0.1", mediaInfo.getRtmpPort(),
					streamId );
			String srcUrl = String.format("rtsp://%s:%s/rtp/%s", "127.0.0.1", mediaInfo.getRtspPort(), streamId);
			JSONObject jsonObject = zlmresTfulUtils.addFFmpegSource(mediaInfo, srcUrl, dstUrl, "1000000", true, false, null);
			logger.info(jsonObject.toJSONString());
			if (jsonObject != null && jsonObject.getInteger("code") == 0) {
				JSONObject data = jsonObject.getJSONObject("data");
				if (data != null) {
					JSONObject result = new JSONObject();
					result.put("key", data.getString("key"));
					StreamInfo streamInfoResult = mediaService.getStreamInfoByAppAndStreamWithCheck("convert", streamId, mediaInfo.getId(), false);
					result.put("StreamInfo", streamInfoResult);
					return result;
				}else {
					throw new ControllerException(ErrorCode.ERROR100.getCode(), "转码失败");
				}
			}else {
				throw new ControllerException(ErrorCode.ERROR100.getCode(), "转码失败");
			}
		}
	}

	/**
	 * 结束转码
	 */
	@Operation(summary = "结束转码")
	@Parameter(name = "key", description = "视频流key", required = true)
	@Parameter(name = "mediaServerId", description = "流媒体服务ID", required = true)
	@PostMapping("/convertStop/{key}")
	public void playConvertStop(@PathVariable String key, String mediaServerId) {
		if (mediaServerId == null) {
			throw new ControllerException(ErrorCode.ERROR400.getCode(), "流媒体：" + mediaServerId + "不存在" );
		}
		MediaServerItem mediaInfo = mediaServerService.getOne(mediaServerId);
		if (mediaInfo == null) {
			throw new ControllerException(ErrorCode.ERROR100.getCode(), "使用的流媒体已经停止运行" );
		}else {
			JSONObject jsonObject = zlmresTfulUtils.delFFmpegSource(mediaInfo, key);
			logger.info(jsonObject.toJSONString());
			if (jsonObject != null && jsonObject.getInteger("code") == 0) {
				JSONObject data = jsonObject.getJSONObject("data");
				if (data == null || data.getBoolean("flag") == null || !data.getBoolean("flag")) {
					throw new ControllerException(ErrorCode.ERROR100 );
				}
			}else {
				throw new ControllerException(ErrorCode.ERROR100 );
			}
		}
	}

	@Operation(summary = "语音广播命令")
	@Parameter(name = "deviceId", description = "设备国标编号", required = true)
    @GetMapping("/broadcast/{deviceId}")
    @PostMapping("/broadcast/{deviceId}")
    public DeferredResult<String> broadcastApi(@PathVariable String deviceId) {
        if (logger.isDebugEnabled()) {
            logger.debug("语音广播API调用");
        }
        Device device = storager.queryVideoDevice(deviceId);
		DeferredResult<String> result = new DeferredResult<>(3 * 1000L);
		String key  = DeferredResultHolder.CALLBACK_CMD_BROADCAST + deviceId;
		if (resultHolder.exist(key, null)) {
			result.setResult("设备使用中");
			return result;
		}
		String uuid  = UUID.randomUUID().toString();
        if (device == null) {

			resultHolder.put(key, key,  result);
			RequestMessage msg = new RequestMessage();
			msg.setKey(key);
			msg.setId(uuid);
			JSONObject json = new JSONObject();
			json.put("DeviceID", deviceId);
			json.put("CmdType", "Broadcast");
			json.put("Result", "Failed");
			json.put("Description", "Device 不存在");
			msg.setData(json);
			resultHolder.invokeResult(msg);
			return result;
		}
		try {
			cmder.audioBroadcastCmd(device, (event) -> {
				RequestMessage msg = new RequestMessage();
				msg.setKey(key);
				msg.setId(uuid);
				JSONObject json = new JSONObject();
				json.put("DeviceID", deviceId);
				json.put("CmdType", "Broadcast");
				json.put("Result", "Failed");
				json.put("Description", String.format("语音广播操作失败，错误码： %s, %s", event.statusCode, event.msg));
				msg.setData(json);
				resultHolder.invokeResult(msg);
			});
		} catch (InvalidArgumentException | SipException | ParseException e) {
			logger.error("[命令发送失败] 语音广播: {}", e.getMessage());
			throw new ControllerException(ErrorCode.ERROR100.getCode(), "命令发送失败: " + e.getMessage());
		}

		result.onTimeout(() -> {
			logger.warn("语音广播操作超时, 设备未返回应答指令");
			RequestMessage msg = new RequestMessage();
			msg.setKey(key);
			msg.setId(uuid);
			JSONObject json = new JSONObject();
			json.put("DeviceID", deviceId);
			json.put("CmdType", "Broadcast");
			json.put("Result", "Failed");
			json.put("Error", "Timeout. Device did not response to broadcast command.");
			msg.setData(json);
			resultHolder.invokeResult(msg);
		});
		resultHolder.put(key, uuid, result);
		return result;
	}

	@Operation(summary = "获取所有的ssrc")
	@GetMapping("/ssrc")
	public JSONObject getSSRC() {
		if (logger.isDebugEnabled()) {
			logger.debug("获取所有的ssrc");
		}
		JSONArray objects = new JSONArray();
		List<SsrcTransaction> allSsrc = streamSession.getAllSsrc();
		for (SsrcTransaction transaction : allSsrc) {
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("deviceId", transaction.getDeviceId());
			jsonObject.put("channelId", transaction.getChannelId());
			jsonObject.put("ssrc", transaction.getSsrc());
			jsonObject.put("streamId", transaction.getStream());
			objects.add(jsonObject);
		}

		JSONObject jsonObject = new JSONObject();
		jsonObject.put("data", objects);
		jsonObject.put("count", objects.size());
		return jsonObject;
	}

}

