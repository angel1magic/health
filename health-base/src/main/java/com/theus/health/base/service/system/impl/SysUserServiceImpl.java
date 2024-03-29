package com.theus.health.base.service.system.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.theus.health.base.common.constants.SysConstants;
import com.theus.health.base.config.jwt.JwtToken;
import com.theus.health.base.mapper.system.SysUserMapper;
import com.theus.health.base.model.dto.SignInDTO;
import com.theus.health.base.model.dto.system.user.FindUserDTO;
import com.theus.health.base.model.dto.system.user.ResetPasswordDTO;
import com.theus.health.base.model.dto.system.user.UserAddDTO;
import com.theus.health.base.model.dto.system.user.UserUpdateDTO;
import com.theus.health.base.model.po.system.SysResource;
import com.theus.health.base.model.po.system.SysRole;
import com.theus.health.base.model.po.system.SysUser;
import com.theus.health.base.model.po.system.SysUserRole;
import com.theus.health.base.model.vo.SysUserVO;
import com.theus.health.base.service.global.ShiroService;
import com.theus.health.base.service.system.SysResourceService;
import com.theus.health.base.service.system.SysRoleService;
import com.theus.health.base.service.system.SysUserRoleService;
import com.theus.health.base.service.system.SysUserService;
import com.theus.health.base.util.LoginUtil;
import com.theus.health.core.bean.ResponseCode;
import com.theus.health.core.exception.BusinessException;
import com.theus.health.core.util.Encrypt;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.DisabledAccountException;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author tangwei
 * @date 2019/5/5 11:23
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Resource
    private SysRoleService roleService;
    @Resource
    private SysUserRoleService userRoleService;
    @Resource
    private SysResourceService resourceService;
    @Resource
    private ShiroService shiroService;

    @Override
    public SysUser findUserByName(String name, boolean hasResource) {
        SysUser user = this.getOne(new QueryWrapper<SysUser>().eq("name", name));
        if (user == null) {
            return null;
        }
        user.setRoles(roleService.findAllRoleByUserId(user.getId(), hasResource));
        return user;
    }

    @Override
    public SysUser findUserById(String id, boolean hasResource) {
        SysUser user = this.getById(id);
        if (user == null) {
            return null;
        }
        user.setRoles(roleService.findAllRoleByUserId(user.getId(), false));
        return user;
    }

    @Override
    public void signIn(SignInDTO signInDTO) {
        if ("".equals(signInDTO.getUsername()) || "".equals(signInDTO.getPassword())) {
            throw new BusinessException(ResponseCode.SING_IN_INPUT_EMPTY);
        }
        JwtToken token = new JwtToken(null, signInDTO.getUsername(), signInDTO.getPassword());
        Subject subject = SecurityUtils.getSubject();
        try {
            subject.login(token);
            if (!subject.isAuthenticated()) {
                throw new BusinessException(ResponseCode.SIGN_IN_INPUT_FAIL);
            }
        } catch (DisabledAccountException e) {
            throw new BusinessException(ResponseCode.SIGN_IN_INPUT_FAIL.code, e.getMessage(), e);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.SIGN_IN_FAIL, e);
        }
    }

    @Override
    public SysUserVO getCurrentUser() {
        HttpServletRequest request =
                ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getRequest();
        boolean b = LoginUtil.executeLogin(request);
        if (!b) {
            throw BusinessException.fail("身份已过期或无效，请重新认证");
        }
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            throw new BusinessException(ResponseCode.NOT_SING_IN);
        }
        JwtToken jwtToken = new JwtToken();
        Object principal = subject.getPrincipal();
        if (principal == null) {
            throw BusinessException.fail("用户信息获取失败");
        }
        BeanUtils.copyProperties(principal, jwtToken);
        SysUser user = this.findUserByName(jwtToken.getUsername(), false);
        if (user == null) {
            throw BusinessException.fail("用户不存在");
        }
        //获取菜单/权限信息
        List<SysResource> allPer = userRolesRegexResource(roleService.findAllRoleByUserId(user.getId(), true));
        SysUserVO vo = new SysUserVO();
        BeanUtils.copyProperties(user, vo);
        vo.setResources(allPer);
        return vo;
    }

    @Override
    public List<SysResource> userRolesRegexResource(List<SysRole> roles) {
        if (roles != null && roles.size() > 0) {
            Map<String, SysResource> resourceMap = new LinkedHashMap<>();
            roles.forEach(role -> {
                if (role.getResources() != null && role.getResources().size() > 0) {
                    //含有则不覆盖
                    role.getResources().forEach(resource ->
                            resourceMap.putIfAbsent(resource.getId(), resource));
                }
            });
            Map<String, SysResource> cacheMap = new ConcurrentHashMap<>();
            List<SysResource> resourceList = new CopyOnWriteArrayList<>();
            resourceMap.forEach((k, v) -> {
                SysResource allParent = resourceService.getResourceAllParent(v, cacheMap, resourceMap);
                //判断是否已经包含此对象
                if (!resourceList.contains(allParent)) {
                    resourceList.add(allParent);
                }
            });
            return resourceList;
        }
        return null;
    }

    @Override
    public IPage<SysUserVO> getAllUserBySplitPage(FindUserDTO findUserDTO) {
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc(true, "create_time");
        IPage<SysUser> userPage = this.page(new Page<>(findUserDTO.getPageNum(),
                findUserDTO.getPageSize()), wrapper);
        IPage<SysUserVO> userVOPage = new Page<>();
        BeanUtils.copyProperties(userPage, userVOPage);
        List<SysUserVO> userVOS = new ArrayList<>();
        userPage.getRecords().forEach(v -> {
            SysUserVO vo = new SysUserVO();
            BeanUtils.copyProperties(v, vo);
            //查找匹配所有用户的角色
            vo.setRoles(roleService.findAllRoleByUserId(v.getId(), false));
            userVOS.add(vo);
        });
        userVOPage.setRecords(userVOS);
        return userVOPage;
    }

    @Override
    public IPage<SysUser> findPage(FindUserDTO findUserDTO) {
        IPage<SysUser> userPage = new Page<>(findUserDTO.getPageNum(),
                findUserDTO.getPageSize());

        userPage.setRecords(this.baseMapper.findPage(userPage,findUserDTO));

        userPage.getRecords().forEach(v -> {
            //查找匹配所有用户的角色
            v.setRoles(roleService.findAllRoleByUserId(v.getId(), false));
        });
        return userPage;
    }

    @Override
    public void statusChange(String userId, Integer status) {
        SysUser user = this.getById(userId);
        if (user == null) {
            throw BusinessException.fail("用户不存在");
        }
        SysUser sysUser = new SysUser();
        BeanUtils.copyProperties(SecurityUtils.getSubject().getPrincipal(), sysUser);
        if (user.getName().equals(sysUser.getName())) {
            throw BusinessException.fail("不能锁定自己的账户");
        }
        user.setStatus(status);
        try {
            this.updateById(user);
            //若为0 需要进行清除登陆授权以及权限信息
            /*if(status==0){

            }*/
            shiroService.clearAuthByUserId(userId, true, true);
        } catch (Exception e) {
            throw BusinessException.fail("操作失败", e);
        }
    }

    @Override
    public void removeUser(String userId) {
        SysUser user = this.getById(userId);
        if (user == null) {
            throw BusinessException.fail("用户不存在！");
        }
        SysUser sysUser = new SysUser();
        BeanUtils.copyProperties(SecurityUtils.getSubject().getPrincipal(), sysUser);
        if (user.getName().equals(sysUser.getName())) {
            throw BusinessException.fail("不能删除自己的账户！");
        }
        try {
            // 删除用户角色关系
            userRoleService.remove(new QueryWrapper<SysUserRole>().eq("user_id", user.getId()));
            this.removeById(userId);
            shiroService.clearAuthByUserId(userId, true, true);
        } catch (Exception e) {
            throw BusinessException.fail("删除失败", e);
        }
    }

    @Override
    public void add(UserAddDTO addDTO) {
        SysUser findUser = this.findUserByName(addDTO.getName(), false);
        if (findUser != null) {
            throw BusinessException.fail(String.format("已经存在用户名为 %s 的用户", addDTO.getName()));
        }
        try {
            findUser = new SysUser();
            BeanUtils.copyProperties(addDTO, findUser);
            findUser.setCreateTime(new Date());
            findUser.setPassword(Encrypt.md5(findUser.getPassword() + findUser.getName()));
            this.save(findUser);
            this.updateUserRole(findUser);
        } catch (Exception e) {
            throw BusinessException.fail("添加用户失败", e);
        }
    }

    @Override
    public void update(String id, UserUpdateDTO updateDTO) {
        SysUser user = this.getById(id);
        if (user == null) {
            throw BusinessException.fail(
                    String.format("更新失败，不存在ID为 %s 的用户", id));
        }
        if(SysConstants.ADMIN.equalsIgnoreCase(user.getName())) {
            throw BusinessException.fail("超级管理员不允许修改!");
        }
        SysUser findUser = this.getOne(new QueryWrapper<SysUser>()
                .eq("name", updateDTO.getName()).ne("id", id));
        if (findUser != null) {
            throw BusinessException.fail(
                    String.format("更新失败，已经存在用户名为 %s 的用户", updateDTO.getName()));
        }
        // 修改用户, 且修改了密码
        if(!updateDTO.getPassword().equals(user.getPassword())
           && !updateDTO.getPassword().equals(Encrypt.md5(user.getPassword() + user.getName()))) {
            updateDTO.setPassword(Encrypt.md5(updateDTO.getPassword() + updateDTO.getName()));
        }
        BeanUtils.copyProperties(updateDTO, user);
        try {
            this.updateById(user);
            this.updateUserRole(user);
            shiroService.clearAuthByUserId(user.getId(), true, false);
        } catch (BusinessException e) {
            throw BusinessException.fail(e.getMsg(), e);
        } catch (Exception e) {
            throw BusinessException.fail("用户信息更新失败", e);
        }
    }

    @Override
    public void updateUserRole(SysUser user) {
        try {
            userRoleService.remove(new QueryWrapper<SysUserRole>().eq("user_id", user.getId()));
            if (user.getRoles() != null && user.getRoles().size() > 0) {
                user.getRoles().forEach(v -> userRoleService.save(SysUserRole.builder()
                        .userId(user.getId())
                        .roleId(v.getId()).build()));
            }
        } catch (Exception e) {
            throw BusinessException.fail("用户权限关联失败", e);
        }
    }

    @Override
    public void resetPassword(ResetPasswordDTO resetPasswordDTO) {
        SysUser user = this.getById(resetPasswordDTO.getUid().trim());
        if (user == null) {
            throw BusinessException.fail(String.format("不存在ID为 %s 的用户", resetPasswordDTO.getUid()));
        }
        String password = Encrypt.md5(resetPasswordDTO.getPassword() + user.getName());
        user.setPassword(password);
        try {
            this.updateById(user);
            shiroService.clearAuthByUserId(user.getId(), true, true);
        } catch (Exception e) {
            throw BusinessException.fail(String.format("ID为 %s 的用户密码重置失败", resetPasswordDTO.getUid()), e);
        }
    }

    @Override
    public Set<String> findPermissions(String userName) {
        Set<String> perms = new HashSet<>();
        List<SysResource> sysResources = resourceService.findByUser(userName);
        for(SysResource sysResource:sysResources) {
            perms.add(sysResource.getPermission());
        }
        return perms;
    }
}
