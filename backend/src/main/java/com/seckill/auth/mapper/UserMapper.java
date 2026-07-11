package com.seckill.auth.mapper;

import com.seckill.auth.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 使用者資料存取(SQL 手寫於 UserMapper.xml,參數一律 #{})。 */
@Mapper
public interface UserMapper {

    int insert(User user);

    User findByUsername(@Param("username") String username);

    User findById(@Param("id") long id);

    boolean existsByUsername(@Param("username") String username);
}
