package com.jiangpa.service.impl;

import com.jiangpa.common.CacheKeys;
import com.jiangpa.dto.UpdateNicknameDTO;
import com.jiangpa.dto.UpdatePasswordDTO;
import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.dto.UserRoleUpdateDTO;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.mapper.UserMapper;
import com.jiangpa.pojo.User;
import com.jiangpa.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单元测试 —— <b>本测试的核心价值是锁住「权限判断」与「作废凭证」</b>。
 *
 * <p>为什么这两个点非测不可（都是真踩过的坑）：
 *
 * <ol>
 *   <li><b>权限判断写反时，大部分用例还是绿的。</b> {@code selectUser} / {@code delete} 的规则是
 *       "自己 <b>或</b> 管理员"，拒绝条件必须写成 {@code !A && !B}。曾经写成 {@code !A || !B}
 *       （见第六节之二缺陷 2），结果<b>自己看不了自己、管理员也看不了别人</b>。
 *       如果只测"该拒的拒了"，两种写法都会通过 —— 必须把<b>"该放的也放行"</b>一起写成断言。</li>
 *   <li><b>"改角色要无条件作废旧凭证"是防降级失效的关键。</b> 曾经把删 refresh key 写在
 *       {@code if (目标是管理员)} 内部（见缺陷 4），导致"降级普通用户"这条最常用的路径根本不清凭证，
 *       旧 refreshToken 能一直续出带旧 role 的新 token，降级形同虚设。
 *       这里用 {@code verify} 盯住"降级普通用户时也删了"。</li>
 * </ol>
 *
 * <p>另外这一层是"数据库不建外键、业务规则全在应用层"的落点（见第七节第 2 条），
 * 所以存在性校验、归属校验、查重、守卫这几类分支都必须有用例兜住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplTest {

    // ==================== 测试常量 ====================

    /** 自己的 id */
    private static final Long SELF_ID = 1L;
    /** 别人的 id */
    private static final Long OTHER_ID = 2L;
    private static final String USERNAME = "zhangsan";
    private static final String NICKNAME = "张三";
    private static final String ENCODED = "$2a$10$encoded";
    private static final Integer ROLE_USER = 0;
    private static final Integer ROLE_ADMIN = 1;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(passwordEncoder, userMapper, stringRedisTemplate);
    }

    // ==================== 测试工具 ====================

    /** 造一个用户；password 传 null 表示不需要密码的用例 */
    private User user(Long id, Integer role, String password) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setUsername(USERNAME);
        u.setNickname(NICKNAME);
        u.setPassword(password);
        return u;
    }

    private UpdatePasswordDTO passwordDTO(String oldPwd, String newPwd, String confirmPwd) {
        UpdatePasswordDTO dto = new UpdatePasswordDTO();
        dto.setOldPassword(oldPwd);
        dto.setNewPassword(newPwd);
        dto.setConfirmNewPassword(confirmPwd);
        return dto;
    }

    /** 断言抛出的 BusinessException 的 code（HTTP 全 200，业务码才是判据） */
    private BusinessException assertBusinessCode(int expectedCode,
                                                org.junit.jupiter.api.function.Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(expectedCode, ex.getCode(), "业务码不对：实际=" + ex.getCode() + "，消息=" + ex.getMessage());
        return ex;
    }

    // ==================== register ====================

    @Test
    @DisplayName("register：新用户名可用 → 存的是加密后的密码，且角色硬编码为 0")
    void registerEncodesPasswordAndForcesRoleZero() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("123456");

        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("123456")).thenReturn(ENCODED);

        UserVO vo = userService.register(dto);

        assertEquals(USERNAME, vo.getUsername());
        // 昵称为空时兜底用用户名，不能存 null（NOT NULL DEFAULT 0 那条约定同理）
        assertEquals(USERNAME, vo.getNickname());
        assertEquals(ROLE_USER, vo.getRole(), "注册必须硬编码 role=0（fail-safe：忘了赋值只会权限不足）");

        verify(userMapper).insert(argThat(u ->
                ENCODED.equals(u.getPassword())
                        && ROLE_USER.equals(u.getRole())
                        && u.getCreateTime() != null
                        && u.getUpdateTime() != null));
    }

    @Test
    @DisplayName("register：用户名已存在 → 400，且不写库")
    void registerRejectsDuplicateUsername() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("123456");

        when(userMapper.selectOne(any())).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        assertBusinessCode(400, () -> userService.register(dto));
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("register：昵称是纯空格 → 也要兜底成用户名（trim 判空）")
    void registerFallsBackWhenNicknameIsBlank() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("123456");
        dto.setNickname("   ");

        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED);

        UserVO vo = userService.register(dto);

        assertEquals(USERNAME, vo.getNickname());
    }

    @Test
    @DisplayName("register：传了昵称 → 用传进来的，不要覆盖成用户名")
    void registerKeepsProvidedNickname() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("123456");
        dto.setNickname("自定义昵称");

        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED);

        UserVO vo = userService.register(dto);

        assertEquals("自定义昵称", vo.getNickname());
    }

    // ==================== authenticate ====================

    @Test
    @DisplayName("authenticate：用户名密码都对 → 返回 UserVO，且【不含 password】")
    void authenticateReturnsVoWithoutPassword() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("123456");

        when(userMapper.selectOne(any())).thenReturn(user(SELF_ID, ROLE_ADMIN, ENCODED));
        when(passwordEncoder.matches("123456", ENCODED)).thenReturn(true);

        UserVO vo = userService.authenticate(dto);

        assertEquals(SELF_ID, vo.getId());
        assertEquals(ROLE_ADMIN, vo.getRole());
        // UserVO 里根本没有 password 字段 —— 这条断言是防止以后有人往 VO 里加回去
        assertFalse(java.util.Arrays.stream(UserVO.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("password")), "UserVO 不能有 password 字段");
    }

    @Test
    @DisplayName("authenticate：用户不存在 → 400「用户名或密码错误」")
    void authenticateRejectsUnknownUsername() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername("nobody");
        dto.setPassword("123456");

        when(userMapper.selectOne(any())).thenReturn(null);

        assertBusinessCode(400, () -> userService.authenticate(dto));
    }

    @Test
    @DisplayName("authenticate：密码错 → 400，且提示语与「用户不存在」完全相同（防用户名枚举）")
    void authenticateUsesIdenticalMessageForWrongPassword() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("wrong");

        when(userMapper.selectOne(any())).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));
        when(passwordEncoder.matches("wrong", ENCODED)).thenReturn(false);

        BusinessException ex = assertBusinessCode(400, () -> userService.authenticate(dto));
        assertEquals("用户名或密码错误", ex.getMessage(),
                "两条失败路径的提示语必须一字不差，否则能用来枚举用户名");
    }

    // ==================== selectUser：权限（自己 or 管理员） ====================

    @Test
    @DisplayName("selectUser：查【自己】放行（这条就是 !A || !B 写反时会挂的用例）")
    void selectUserAllowsSelf() {
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));

        UserVO vo = assertDoesNotThrow(() -> userService.selectUser(SELF_ID, SELF_ID, ROLE_USER));

        assertEquals(SELF_ID, vo.getId());
        assertEquals(NICKNAME, vo.getNickname());
    }

    @Test
    @DisplayName("selectUser：管理员查【别人】放行（配对用例，只测'该拒的'拦不住 || 和 && 的区别）")
    void selectUserAllowsAdminOnOthers() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        UserVO vo = assertDoesNotThrow(() -> userService.selectUser(OTHER_ID, SELF_ID, ROLE_ADMIN));

        assertEquals(OTHER_ID, vo.getId());
    }

    @Test
    @DisplayName("selectUser：普通用户查【别人】→ 403")
    void selectUserRejectsOtherForNormalUser() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        assertBusinessCode(403, () -> userService.selectUser(OTHER_ID, SELF_ID, ROLE_USER));
    }

    @Test
    @DisplayName("selectUser：role 为 null（旧数据）→ 当成普通用户拒绝，不能 NPE、也不能放行")
    void selectUserHandlesNullRole() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, null, ENCODED));

        assertBusinessCode(403, () -> userService.selectUser(OTHER_ID, SELF_ID, null));
    }

    @Test
    @DisplayName("selectUser：用户不存在 → 404（存在性校验要在归属校验【之前】）")
    void selectUserReturns404BeforeForbidden() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(null);

        // 关键：调用者是普通用户，如果顺序反了这里会先抛 403，让人误以为是权限问题
        assertBusinessCode(404, () -> userService.selectUser(OTHER_ID, SELF_ID, ROLE_USER));
    }

    // ==================== updateNickname：仅本人（管理员也不行） ====================

    @Test
    @DisplayName("updateNickname：改【自己】→ 落库，且只 set 昵称和更新时间")
    void updateNicknameAllowsSelf() {
        UpdateNicknameDTO dto = new UpdateNicknameDTO();
        dto.setNickname("新昵称");
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));

        assertDoesNotThrow(() -> userService.updateNickname(dto, SELF_ID, SELF_ID));

        verify(userMapper).updateById(argThat(u ->
                "新昵称".equals(u.getNickname()) && u.getUpdateTime() != null));
    }

    @Test
    @DisplayName("updateNickname：改【别人】→ 403，即使调用者是管理员")
    void updateNicknameRejectsAdminOnOthers() {
        UpdateNicknameDTO dto = new UpdateNicknameDTO();
        dto.setNickname("新昵称");
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        // 昵称属个人身份，管理员也没有代改权 —— 这条断言防止以后"顺手"给它加 @RequireRole 或放开
        assertBusinessCode(403, () -> userService.updateNickname(dto, OTHER_ID, SELF_ID));
        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("updateNickname：用户不存在 → 404")
    void updateNicknameReturns404WhenMissing() {
        UpdateNicknameDTO dto = new UpdateNicknameDTO();
        dto.setNickname("新昵称");
        when(userMapper.selectById(OTHER_ID)).thenReturn(null);

        assertBusinessCode(404, () -> userService.updateNickname(dto, OTHER_ID, SELF_ID));
    }

    // ==================== updatePassword：仅本人 + 四个拒绝分支 ====================

    @Test
    @DisplayName("updatePassword：全部合法 → 存新密文 + 【删 refreshKey 强制下线】")
    void updatePasswordStoresHashAndRevokesRefreshKey() {
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));
        when(passwordEncoder.matches("old123", ENCODED)).thenReturn(true);
        when(passwordEncoder.encode("new123")).thenReturn("$2a$10$new");

        assertDoesNotThrow(() -> userService.updatePassword(
                passwordDTO("old123", "new123", "new123"), SELF_ID, SELF_ID));

        verify(userMapper).updateById(argThat(u -> "$2a$10$new".equals(u.getPassword())));
        // 改完密码必须让 refreshToken 立即失效（access 是无状态的，最多还能用 30 分钟，见第七节第 17 条）
        verify(stringRedisTemplate).delete(CacheKeys.tokenRefresh(SELF_ID));
    }

    @Test
    @DisplayName("updatePassword：改【别人】的密码 → 403，且不落库、不清凭证")
    void updatePasswordRejectsOthers() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        assertBusinessCode(403, () -> userService.updatePassword(
                passwordDTO("old123", "new123", "new123"), OTHER_ID, SELF_ID));

        verify(userMapper, never()).updateById(any());
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("updatePassword：用户不存在 → 404")
    void updatePasswordReturns404WhenMissing() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(null);

        assertBusinessCode(404, () -> userService.updatePassword(
                passwordDTO("old123", "new123", "new123"), OTHER_ID, SELF_ID));
    }

    @Test
    @DisplayName("updatePassword：旧密码错 → 400，且不落库")
    void updatePasswordRejectsWrongOldPassword() {
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));
        when(passwordEncoder.matches("wrongOld", ENCODED)).thenReturn(false);

        assertBusinessCode(400, () -> userService.updatePassword(
                passwordDTO("wrongOld", "new123", "new123"), SELF_ID, SELF_ID));

        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("updatePassword：新旧密码相同 → 400（改了个寂寞，要明确拒绝）")
    void updatePasswordRejectsSameAsOld() {
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));
        when(passwordEncoder.matches("same123", ENCODED)).thenReturn(true);

        assertBusinessCode(400, () -> userService.updatePassword(
                passwordDTO("same123", "same123", "same123"), SELF_ID, SELF_ID));

        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("updatePassword：两次输入不一致 → 400")
    void updatePasswordRejectsMismatchedConfirm() {
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));
        when(passwordEncoder.matches("old123", ENCODED)).thenReturn(true);

        assertBusinessCode(400, () -> userService.updatePassword(
                passwordDTO("old123", "new123", "other123"), SELF_ID, SELF_ID));

        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("updatePassword：Redis 挂了删不掉 refreshKey → 仍算改密成功（删除类操作 fail-open）")
    void updatePasswordIsFailOpenWhenRedisDeleteFails() {
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));
        when(passwordEncoder.matches("old123", ENCODED)).thenReturn(true);
        when(passwordEncoder.encode("new123")).thenReturn("$2a$10$new");
        when(stringRedisTemplate.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // 删不掉 refreshKey 不产生"错误的成功语义"：密码已经改了，不该因此告诉用户失败
        assertDoesNotThrow(() -> userService.updatePassword(
                passwordDTO("old123", "new123", "new123"), SELF_ID, SELF_ID));

        verify(userMapper).updateById(any());
    }

    // ==================== delete：自己 or 管理员 ====================

    @Test
    @DisplayName("delete：删【自己】放行")
    void deleteAllowsSelf() {
        when(userMapper.selectById(SELF_ID)).thenReturn(user(SELF_ID, ROLE_USER, ENCODED));

        assertDoesNotThrow(() -> userService.delete(SELF_ID, SELF_ID, ROLE_USER));

        verify(userMapper).deleteById(SELF_ID);
    }

    @Test
    @DisplayName("delete：管理员删【别人】放行")
    void deleteAllowsAdminOnOthers() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        assertDoesNotThrow(() -> userService.delete(OTHER_ID, SELF_ID, ROLE_ADMIN));

        verify(userMapper).deleteById(OTHER_ID);
    }

    @Test
    @DisplayName("delete：普通用户删【别人】→ 403，且不删（就是那个能从 id=1 遍历删光所有人的漏洞）")
    void deleteRejectsOtherForNormalUser() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        assertBusinessCode(403, () -> userService.delete(OTHER_ID, SELF_ID, ROLE_USER));

        // 用 anyLong() 而不是 any()：MyBatis-Plus 3.5.5 的 BaseMapper 有 deleteById(Serializable)
        // 和 deleteById(T) 两个重载，any() 会匹配不上而编译报错
        verify(userMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("delete：用户不存在 → 404")
    void deleteReturns404WhenMissing() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(null);

        assertBusinessCode(404, () -> userService.delete(OTHER_ID, SELF_ID, ROLE_ADMIN));
    }

    // ==================== updateRole：最后一个管理员守卫 + 无条件作废凭证 ====================

    @Test
    @DisplayName("updateRole：升级普通用户 → 落库 role=1 + 删 refreshKey")
    void updateRolePromotesUser() {
        UserRoleUpdateDTO dto = new UserRoleUpdateDTO();
        dto.setId(OTHER_ID);
        dto.setRole(ROLE_ADMIN);
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        assertDoesNotThrow(() -> userService.updateRole(dto));

        verify(userMapper).updateById(argThat(u -> ROLE_ADMIN.equals(u.getRole())));
        verify(stringRedisTemplate).delete(CacheKeys.tokenRefresh(OTHER_ID));
    }

    @Test
    @DisplayName("updateRole：降级普通用户（本来就不是管理员）→ 也要删 refreshKey（缺陷 4 的回归用例）")
    void updateRoleAlwaysRevokesRefreshKeyEvenWhenTargetIsNotAdmin() {
        UserRoleUpdateDTO dto = new UserRoleUpdateDTO();
        dto.setId(OTHER_ID);
        dto.setRole(ROLE_USER);
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));

        assertDoesNotThrow(() -> userService.updateRole(dto));

        // 曾经把删 key 写进 if(目标是管理员) 里，导致这条路径根本不清凭证 → refresh 能续出旧 role
        verify(stringRedisTemplate).delete(CacheKeys.tokenRefresh(OTHER_ID));
    }

    @Test
    @DisplayName("updateRole：降级管理员（还有其他管理员）→ 放行 + 删 refreshKey")
    void updateRoleDemotesAdminWhenOthersRemain() {
        UserRoleUpdateDTO dto = new UserRoleUpdateDTO();
        dto.setId(OTHER_ID);
        dto.setRole(ROLE_USER);
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_ADMIN, ENCODED));
        when(userMapper.selectCount(any())).thenReturn(1L);   // 除目标外还有 1 个管理员

        assertDoesNotThrow(() -> userService.updateRole(dto));

        verify(userMapper).updateById(argThat(u -> ROLE_USER.equals(u.getRole())));
        verify(stringRedisTemplate).delete(CacheKeys.tokenRefresh(OTHER_ID));
    }

    @Test
    @DisplayName("updateRole：想降级【最后一个】管理员 → 403，且不落库、不清凭证")
    void updateRoleRejectsDemotingLastAdmin() {
        UserRoleUpdateDTO dto = new UserRoleUpdateDTO();
        dto.setId(OTHER_ID);
        dto.setRole(ROLE_USER);
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_ADMIN, ENCODED));
        when(userMapper.selectCount(any())).thenReturn(0L);   // 除目标外一个管理员都没有

        assertBusinessCode(403, () -> userService.updateRole(dto));

        verify(userMapper, never()).updateById(any());
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("updateRole：目标不存在 → 404")
    void updateRoleReturns404WhenMissing() {
        UserRoleUpdateDTO dto = new UserRoleUpdateDTO();
        dto.setId(OTHER_ID);
        dto.setRole(ROLE_ADMIN);
        when(userMapper.selectById(OTHER_ID)).thenReturn(null);

        assertBusinessCode(404, () -> userService.updateRole(dto));
    }

    @Test
    @DisplayName("updateRole：Redis 挂了 → 角色照样改成功（fail-open）")
    void updateRoleIsFailOpenWhenRedisDeleteFails() {
        UserRoleUpdateDTO dto = new UserRoleUpdateDTO();
        dto.setId(OTHER_ID);
        dto.setRole(ROLE_ADMIN);
        when(userMapper.selectById(OTHER_ID)).thenReturn(user(OTHER_ID, ROLE_USER, ENCODED));
        when(stringRedisTemplate.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertDoesNotThrow(() -> userService.updateRole(dto));

        verify(userMapper).updateById(any());
    }

    // ==================== toUserVO：唯一映射出口 ====================

    @Test
    @DisplayName("toUserVO：列表查询那种【只查了 4 列】的 User 也要能正确转换（其他字段为 null 不影响）")
    void toUserVoHandlesPartiallyLoadedUser() {
        // 列表接口 wrapper.select 只查 id/role/username/nickname，password 等字段是 null
        User partial = new User();
        partial.setId(SELF_ID);
        partial.setRole(ROLE_ADMIN);
        partial.setUsername(USERNAME);
        partial.setNickname(NICKNAME);

        when(userMapper.selectById(SELF_ID)).thenReturn(partial);

        UserVO vo = userService.selectUser(SELF_ID, SELF_ID, ROLE_USER);

        assertEquals(SELF_ID, vo.getId());
        assertEquals(ROLE_ADMIN, vo.getRole());
        assertEquals(USERNAME, vo.getUsername());
        assertEquals(NICKNAME, vo.getNickname());
    }

    @Test
    @DisplayName("toUserVO：不依赖 id 之外的字段做判断（避免以后有人往里加业务逻辑）")
    void toUserVoDoesNotNeedOtherFields() {
        User bare = new User();
        bare.setId(SELF_ID);

        when(userMapper.selectById(SELF_ID)).thenReturn(bare);

        UserVO vo = assertDoesNotThrow(() -> userService.selectUser(SELF_ID, SELF_ID, ROLE_ADMIN));

        assertEquals(SELF_ID, vo.getId());
        assertNull(vo.getNickname(), "源对象字段为 null 就照实拷成 null，不要自作主张兜底");
    }
}
