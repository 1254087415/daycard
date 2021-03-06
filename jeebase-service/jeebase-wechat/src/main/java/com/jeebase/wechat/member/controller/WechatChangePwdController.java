package com.jeebase.wechat.member.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jeebase.common.annotation.auth.CurrentUser;
import com.jeebase.common.annotation.auth.NoAuthentication;
import com.jeebase.common.annotation.log.AroundLog;
import com.jeebase.common.base.BusinessException;
import com.jeebase.common.base.Constant;
import com.jeebase.common.base.Result;
import com.jeebase.common.base.component.JwtComponent;
import com.jeebase.system.common.service.ISmsService;
import com.jeebase.system.security.entity.User;
import com.jeebase.system.security.service.IResourceService;
import com.jeebase.system.security.service.IUserRoleService;
import com.jeebase.system.security.service.IUserService;
import com.jeebase.wechat.member.dto.RetrievePassword;
import com.jeebase.wechat.member.dto.UpdateMobile;
import com.jeebase.wechat.member.dto.UpdatePassword;
import com.jeebase.wechat.member.entity.WechatMember;
import com.jeebase.wechat.member.service.IWechatMemberService;
import com.jeebase.wechat.register.dto.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.CacheObject;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.annotations.ApiIgnore;

import javax.validation.Valid;
import java.util.List;
import java.util.Random;

@RestController
@RequestMapping("/wechat/change")
@Api(value = "WeChatChangePwdController|????????????????????????????????????????????????????????????")
public class WechatChangePwdController {

    @Autowired
    private IWechatMemberService wechatMemberService;

    @Autowired
    private IUserService userService;

    @Autowired
    private JwtComponent jwtComponent;

    @Autowired
    private IUserRoleService userRoleService;

    @Autowired
    private IResourceService resourceService;

    @Autowired
    ISmsService iSmsService;

    @Value("${system.smsTimes}")
    private int smsTimes;

    @Autowired
    private CacheChannel cacheChannel;

    /**
     * ?????????????????????
     */
    @PostMapping("/sms/send")
    @RequiresAuthentication
    @ApiOperation(value = "?????????????????????????????????????????????")
    public Result<?> sendSmsReg(@Valid @RequestBody WeChatSms smsDto) {
        String phoneNumber = smsDto.getMobile();
        QueryWrapper<User> ew = new QueryWrapper<>();
        ew.eq("user_account", phoneNumber).or().eq("user_email", phoneNumber)
                .or().eq("user_mobile", phoneNumber);
        List<User> userList = userService.list(ew);
        if (!CollectionUtils.isEmpty(userList)) {
            throw new BusinessException("????????????????????????");
        }
        CacheObject smsTimesCache = cacheChannel.get("smsTimes", phoneNumber + "_sms_times");
        Integer vcodeNumbers = (Integer) smsTimesCache.getValue();
        if (null != vcodeNumbers) {
            int num = vcodeNumbers.intValue();
            if (num >= smsTimes) {
                return new Result<>().error("?????????????????????????????????");
            }
            cacheChannel.set("smsTimes", phoneNumber + "_sms_times", num + 1);
        } else {
            cacheChannel.set("smsTimes", phoneNumber + "_sms_times", 1);
        }
        String smsCode = String.valueOf(new Random().nextInt(899999) + 100000);
        System.out.println("?????????????????????" + smsCode);
        iSmsService.sendVcodeSms(phoneNumber, smsCode);
        cacheChannel.set("smsCode", phoneNumber + "_sms_change", smsCode);
        return new Result<>().success("?????????????????????");
    }

    /**
     * ???????????????
     */
    @PostMapping("/mobile")
    @RequiresAuthentication
    @ApiOperation(value = "??????????????????")
    @AroundLog(name = "??????????????????")
    public Result<?> changeMobile(@RequestBody UpdateMobile updateMobile, @ApiIgnore @CurrentUser User currentUser) {
        String phoneNumber = updateMobile.getMobile();
        String userSmsCode = updateMobile.getSmsCode();
        CacheObject smsCodeCache = cacheChannel.get("smsCode", phoneNumber + "_sms_change");
        String smsCode = (String) smsCodeCache.getValue();
        if (StringUtils.isEmpty(smsCode)) {
            return new Result<>().error("??????????????????????????????????????????");
        }
        if (!smsCode.equals(userSmsCode)) {
            return new Result<>().error("???????????????????????????????????????");
        }
        Integer userId = currentUser.getId();
        WechatMember wechatMember = new WechatMember();
        wechatMember.setUserId(userId);
        UpdateWrapper<WechatMember> wrapper = new UpdateWrapper<WechatMember>();
        wrapper.eq("user_id",userId);
        boolean result = wechatMemberService.update(wechatMember,wrapper);

        User userCache = userService.getById(userId);
        cacheChannel.evict("users","account_" + userCache.getUserAccount());

        User user = new User();
        user.setId(userId);
        user.setUserMobile(phoneNumber);
        result = userService.updateById(user);
        if (result) {
            return new Result<>().success("????????????");
        } else {
            return new Result<>().error("????????????????????????");
        }
    }

    /**
     * ???????????????
     */
    @PostMapping("/password")
    @RequiresAuthentication
    @ApiOperation(value = "????????????")
    @AroundLog(name = "????????????")
    public Result<?> changePassword(@RequestBody UpdatePassword updatePassword, @ApiIgnore @CurrentUser User currentUser) {
        User user = userService.getById(currentUser.getId());
        if (StringUtils.isEmpty(user) || !BCrypt.checkpw(updatePassword.getOldPassword(), user.getUserPassword())) {
            return new Result<String>().error("???????????????");
        }
        Integer userId = currentUser.getId();
        User userUpdate = new User();
        userUpdate.setId(userId);
        String cryptPwd = BCrypt.hashpw(updatePassword.getPassword(), BCrypt.gensalt());
        userUpdate.setUserPassword(cryptPwd);
        boolean result = userService.updateById(userUpdate);
        String token = jwtComponent.sign(user.getUserAccount(), cryptPwd, Constant.ExpTimeType.WEB);
        if (result) {
            cacheChannel.evict("users","account_" + currentUser.getUserAccount());
            return new Result<>().success("????????????").put(token);
        } else {
            return new Result<>().error("????????????????????????");
        }
    }

    /**
     * ?????????????????????
     */
    @PostMapping("/retrieve/sms/send")
    @NoAuthentication
    @ApiOperation(value = "???????????????????????????????????????")
    public Result<?> sendRetrieveSms(@Valid @RequestBody WeChatSms smsDto) {
        String phoneNumber = smsDto.getMobile();
        QueryWrapper<User> ew = new QueryWrapper<>();
        ew.eq("user_account", phoneNumber).or().eq("user_email", phoneNumber)
                .or().eq("user_mobile", phoneNumber);
        List<User> userList = userService.list(ew);
        if (CollectionUtils.isEmpty(userList)) {
            throw new BusinessException("???????????????");
        }
        CacheObject smsTimesCache = cacheChannel.get("smsTimes", phoneNumber + "_sms_times");
        Integer vcodeNumbers = (Integer) smsTimesCache.getValue();
        if (null != vcodeNumbers) {
            int num = vcodeNumbers.intValue();
            if (num >= smsTimes) {
                return new Result<>().error("?????????????????????????????????");
            }
            cacheChannel.set("smsTimes", phoneNumber + "_sms_times", num + 1);
        } else {
            cacheChannel.set("smsTimes", phoneNumber + "_sms_times", 1);
        }
        String smsCode = String.valueOf(new Random().nextInt(899999) + 100000);
        System.out.println("?????????????????????" + smsCode);
        iSmsService.sendVcodeSms(phoneNumber, smsCode);
        cacheChannel.set("smsCode", phoneNumber + "_sms_retrieve", smsCode);
        return new Result<>().success("?????????????????????");
    }

    /**
     * ????????????
     */
    @PostMapping("/retrieve/password")
    @NoAuthentication
    @ApiOperation(value = "????????????")
    @AroundLog(name = "????????????")
    public Result<?> retrievePassword(@RequestBody RetrievePassword retrievePassword) {
        String phoneNumber = retrievePassword.getMobile();
        String userSmsCode = retrievePassword.getSmsCode();
        CacheObject smsCodeCache = cacheChannel.get("smsCode", phoneNumber + "_sms_retrieve");
        String smsCode = (String) smsCodeCache.getValue();
        if (StringUtils.isEmpty(smsCode)) {
            return new Result<>().error("??????????????????????????????????????????");
        }
        if (!smsCode.equals(userSmsCode)) {
            return new Result<>().error("???????????????????????????????????????");
        }

        String password =  retrievePassword.getPassword();
        UpdateWrapper<User> wrapper = new UpdateWrapper<User>();
        wrapper.eq("user_mobile", phoneNumber);
        User user = new User();
        String cryptPwd = BCrypt.hashpw(password, BCrypt.gensalt());
        user.setUserPassword(cryptPwd);
        boolean result = userService.update(user,wrapper);
        if (result) {
            QueryWrapper<User> wrapperq = new QueryWrapper<User>();
            wrapperq.eq("user_mobile", phoneNumber);
            User userCache = userService.getOne(wrapperq);
            cacheChannel.evict("users","account_" + userCache.getUserAccount());
            return new Result<>().success("????????????");
        } else {
            return new Result<>().error("????????????????????????");
        }
    }
}
