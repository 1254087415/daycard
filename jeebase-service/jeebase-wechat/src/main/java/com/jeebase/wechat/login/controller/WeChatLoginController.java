package com.jeebase.wechat.login.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jeebase.common.annotation.auth.CurrentUser;
import com.jeebase.common.annotation.auth.NoAuthentication;
import com.jeebase.common.base.BusinessException;
import com.jeebase.common.base.Constant;
import com.jeebase.common.base.ResponseConstant;
import com.jeebase.common.base.Result;
import com.jeebase.common.base.component.JwtComponent;
import com.jeebase.system.common.domain.QueryOpenIdResponse;
import com.jeebase.system.common.domain.SnsUserInfo;
import com.jeebase.system.common.service.ISmsService;
import com.jeebase.system.common.service.IWechatApiService;
import com.jeebase.system.security.entity.User;
import com.jeebase.system.security.service.IResourceService;
import com.jeebase.system.security.service.IUserRoleService;
import com.jeebase.system.security.service.IUserService;
import com.jeebase.wechat.login.dto.TokenUser;
import com.jeebase.wechat.login.dto.WebChatLoginUser;
import com.jeebase.wechat.member.entity.WechatMember;
import com.jeebase.wechat.member.service.IWechatMemberService;
import com.jeebase.wechat.wechat.entity.Wechat;
import com.jeebase.wechat.wechat.service.IWechatService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import net.oschina.j2cache.CacheChannel;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cglib.beans.BeanCopier;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.annotations.ApiIgnore;

import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;

@RestController
@RequestMapping("/wechat/auth")
@Api(value = "WeChatLoginController|?????????????????????????????????????????????")
public class WeChatLoginController {

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    
    @Autowired
    private JwtComponent jwtComponent;
    
    @Autowired
    private IWechatApiService weiXinApiService;

    @Autowired
    private IUserService userService;
    
    @Autowired
    private IWechatMemberService wechatMemberService;

    @Autowired
    private IWechatService wechatService;

    @Autowired
    private IUserRoleService userRoleService;

    @Autowired
    private IResourceService resourceService;

    @Autowired
    ISmsService iSmsService;

    @Value("${system.smsTimes}")
    private int smsTimes;
    
    @Value("${weixin.api.appId}")
    private String appId;
    
    @Value("${weixin.api.secret}")
    private String appSecret;

    @Value("${weixin.backUrl}")
    private String backUrl;

    @Autowired
    private CacheChannel cacheChannel;
    
    @PostMapping("/url")
    @NoAuthentication
    @ApiOperation(value = "??????????????????url", notes = "??????????????????url")
    public Result<String> getAuthUrl(String state) throws Exception {
        String urlback = URLEncoder.encode(backUrl, "utf-8");
        String url = "https://open.weixin.qq.com/connect/oauth2/authorize?appid="+appId+"&redirect_uri="+urlback+"&response_type=code&scope=snsapi_userinfo&state=" + state + "#wechat_redirect";
        return new Result<String>().success().put(url);
    }
    
    
    @PostMapping("/login")
    @NoAuthentication
    @ApiOperation(value = "????????????", notes = "??????token")
    public Result<TokenUser> login( String code, HttpServletRequest request) throws Exception {
        TokenUser tokenUser = new TokenUser();
        QueryOpenIdResponse queryOpenIdResponse = weiXinApiService.queryAppId(appId, appSecret, code);
        String openId = queryOpenIdResponse.getOpenid();
        if (StringUtils.isEmpty(openId))
        {
            throw new BusinessException("?????????????????????????????????????????????");
        }
        String accessToken = queryOpenIdResponse.getAccess_token();
        SnsUserInfo snsUserInfo = weiXinApiService.querySnsUserInfo(accessToken, openId);
        tokenUser.setReg(false);
        tokenUser.setOpenId(snsUserInfo.getOpenid());
        // ????????????????????????
        QueryWrapper<WechatMember> ew = new QueryWrapper<>();
        ew.eq("wechat_platform_open_id", openId);
        WechatMember user = wechatMemberService.getOne(ew);
        //??????????????????????????????????????????
        if(null == user)
        {
            WechatMember addUser = new WechatMember();
            addUser.setAvatarUrl(snsUserInfo.getHeadimgurl());
            addUser.setNickname(snsUserInfo.getNickname());
            addUser.setContry(snsUserInfo.getCountry());
            addUser.setProvince(snsUserInfo.getProvince());
            addUser.setCity(snsUserInfo.getCity());
            addUser.setWechatPlatformOpenId(snsUserInfo.getOpenid());
            addUser.setWechatUnionId(snsUserInfo.getUnionid());
            wechatMemberService.save(addUser);
            try
            {
                Wechat wechat = new Wechat();
                BeanCopier.create(SnsUserInfo.class, Wechat.class, false).copy(snsUserInfo,
                        wechat, null);
                wechatService.save(wechat);
            }
            catch (Exception e)
            {
                logger.error("???????????????????????????", e);
            }
        }
        //??????????????????????????????
        else
        {
            WechatMember updateUser = new WechatMember();
            updateUser.setId(user.getId());
            updateUser.setAvatarUrl(snsUserInfo.getHeadimgurl());
            updateUser.setNickname(snsUserInfo.getNickname());
            wechatMemberService.updateById(updateUser);
            Integer userId = user.getUserId();
            if(!StringUtils.isEmpty(userId))
            {
                // ????????????????????????
                User userQ = userService.getById(userId);
                String token = jwtComponent.sign(userQ.getUserAccount(), userQ.getUserPassword(), Constant.ExpTimeType.APP);
                tokenUser.setReg(true);
                tokenUser.setOpenId(null);
                tokenUser.setToken(token);
            }
        }
        return new Result<TokenUser>().success().put(tokenUser);
    }

    @PostMapping("/username/login")
    @NoAuthentication
    @ApiOperation(value = "????????????", notes = "??????token")
    public Result<TokenUser> usernameLogin(@RequestBody WebChatLoginUser loginUser) throws Exception {

        TokenUser tokenUser = new TokenUser();
        String openId = loginUser.getOpenId();
        String userAccount = loginUser.getMobile();
        String userPassword = loginUser.getPassword();

        // ????????????????????????
        QueryWrapper<User> ew = new QueryWrapper<>();
        ew.eq("user_account", userAccount).or().eq("user_mobile", userAccount).or().eq("user_email", userAccount);
        User user = userService.getOne(ew);

        if(null != user && !"1".equals(user.getUserStatus()))
        {
            return new Result<TokenUser>().error("????????????????????????????????????????????????");
        }

        if (null == user || !BCrypt.checkpw(user.getUserAccount() + userPassword, user.getUserPassword())) {
            return new Result<TokenUser>().error(ResponseConstant.INVALID_USERNAME_PASSWORD);
        }

        if(StringUtils.isEmpty(openId))
        {
            return new Result<TokenUser>().error("????????????????????????id???");
        }

        // ??????openId????????????
        QueryWrapper<WechatMember> ewhbg = new QueryWrapper<WechatMember>();
        ewhbg.eq("wechat_platform_open_id", openId);
        WechatMember wechatMember = wechatMemberService.getOne(ewhbg);
        if (null != wechatMember) {
            //??????????????????????????????id????????????id??????????????????id????????????????????????????????????
            if(!StringUtils.isEmpty(wechatMember.getUserId()) && wechatMember.getUserId().equals(user.getId()))
            {
                WechatMember hUser = new WechatMember();
                hUser.setId(wechatMember.getId());
                hUser.setRemember(loginUser.isRemember());
                wechatMemberService.updateById(hUser);
                tokenUser.setReg(true);
                tokenUser.setUserType(wechatMember.getUserType());
            }
            //??????????????????????????????id????????????id???????????????id????????????????????????????????????????????????????????????????????????
            else if(!StringUtils.isEmpty(wechatMember.getUserId()) && !wechatMember.getUserId().equals(user.getId()))
            {
                QueryWrapper<WechatMember> queryhbg = new QueryWrapper<WechatMember>();
                queryhbg.eq("user_id",user.getId());
                WechatMember huserq = wechatMemberService.getOne(queryhbg);

                //?????????????????????
                UpdateWrapper<WechatMember> updateMember = new UpdateWrapper();
				updateMember.set("wechat_open_id",null)
                        .set("wechat_platform_open_id",null).set("wechat_union_id",null).eq("id",huserq.getId());
                wechatMemberService.update(updateMember);

                //??????????????????
                WechatMember hUserNew = new WechatMember();
                hUserNew.setId(wechatMember.getId());
                hUserNew.setWechatOpenId(huserq.getWechatOpenId());
                hUserNew.setWechatPlatformOpenId(huserq.getWechatPlatformOpenId());
                hUserNew.setWechatUnionId(huserq.getWechatUnionId());
                hUserNew.setNickname(huserq.getNickname());
                hUserNew.setAvatarUrl(huserq.getAvatarUrl());
                hUserNew.setRemember(loginUser.isRemember());
                wechatMemberService.updateById(hUserNew);

                tokenUser.setReg(true);
                tokenUser.setUserType(huserq.getUserType());
            }
            //??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            else if(StringUtils.isEmpty(wechatMember.getUserId()) && !StringUtils.isEmpty(user.getId()))
            {
                QueryWrapper<WechatMember> queryhbg = new QueryWrapper<WechatMember>();
                queryhbg.eq("user_id",user.getId());
                WechatMember huserq = wechatMemberService.getOne(queryhbg);
                if (null != huserq)
                {   
	                //??????
	                WechatMember hUserNew = new WechatMember();
	                hUserNew.setId(wechatMember.getId());
	                hUserNew.setWechatOpenId(huserq.getWechatOpenId());
	                hUserNew.setWechatPlatformOpenId(huserq.getWechatPlatformOpenId());
	                hUserNew.setWechatUnionId(huserq.getWechatUnionId());
	                hUserNew.setNickname(huserq.getNickname());
	                hUserNew.setAvatarUrl(huserq.getAvatarUrl());
	                hUserNew.setRemember(loginUser.isRemember());
	                wechatMemberService.updateById(hUserNew);
                }
                tokenUser.setReg(true);
                tokenUser.setUserType(huserq.getUserType());
            }
        }
        else
        {
            return new Result<TokenUser>().error("???????????????????????????????????????????????????????????????");
        }
        String token = jwtComponent.sign(user.getUserAccount(), user.getUserPassword(), Constant.ExpTimeType.WEB);
        tokenUser.setToken(token);
        return new Result<TokenUser>().success().put(tokenUser);
    }

    @PostMapping("/logout")
    @RequiresAuthentication
    @ApiOperation(value = "????????????")
    public Result<?> logOut( @ApiIgnore @CurrentUser User currentUser) throws Exception {
        Integer userId = currentUser.getId();
		QueryWrapper<WechatMember> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id",userId);
        WechatMember wechatMember = wechatMemberService.getOne(queryWrapper, false);

        WechatMember wechatMemberQuery = new WechatMember();
        wechatMemberQuery.setWechatPlatformOpenId(wechatMember.getWechatPlatformOpenId());
        WechatMember oldWechatMember = wechatMemberService.getSoftDeleteWechatMember(wechatMemberQuery);
        if (null != oldWechatMember)
        {
            wechatMemberService.recoverSoftDeleteWechatMember(oldWechatMember);
        }
        else
        {
            QueryWrapper<Wechat> queryWrapperWeiXin = new QueryWrapper<>();
            queryWrapperWeiXin.eq("openid", wechatMember.getWechatPlatformOpenId());
            Wechat wechat = wechatService.getOne(queryWrapperWeiXin, false);
            WechatMember addUser = new WechatMember();
            addUser.setAvatarUrl(wechat.getHeadimgurl());
            addUser.setNickname(wechat.getNickname());
            addUser.setContry(wechat.getCountry());
            addUser.setProvince(wechat.getProvince());
            addUser.setCity(wechat.getCity());
            addUser.setWechatPlatformOpenId(wechat.getOpenid());
            addUser.setWechatUnionId(wechat.getUnionid());
            addUser.setCreateTime(wechat.getCreateTime());
            wechatMemberService.save(addUser);
        }
        UpdateWrapper<WechatMember> updateMember = new UpdateWrapper();
        updateMember.set("wechat_open_id",null)
                .set("wechat_platform_open_id",null).set("wechat_union_id",null).eq("user_id",userId);
        wechatMemberService.update(updateMember);
        return new Result<>().success();
    }
}
