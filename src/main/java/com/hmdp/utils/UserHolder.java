package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
    //这后面新增的完全是因为 这个项目搞得usetDTO和user有点乱
    private static final ThreadLocal<User> tl1 = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }
    public  static void saveUser(User user){tl1.set(user);}


    public static UserDTO getUser(){
        return tl.get();
    }

    //这完全
    public static User getUser1(){
        return tl1.get();
    }



    public static void removeUser(){
        tl.remove();
    }
}
