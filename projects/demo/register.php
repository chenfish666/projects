<?php
session_start();
$conn=require_once "config.php";
if (isset($_POST['username']) && isset($_POST['password']) && isset($_POST['password_check'])){


    @$username = $_POST['username'];
    @$password = $_POST['password'];
    @$password_check = $_POST['password_check'];

    // $user_data = 'username='. $username;
    // echo $user_data;

    if (empty($username)){
        header("location:register.php?error=User Name is required&$user_data");
        exit();
    }else if(empty($password)){
        header("location:register.php?error=User Password is required&$user_data");
        exit();
    }else if(empty($password_check)){
        header("location:register.php?error=User Password_check is required&$user_data");
        exit();
    }else if($password != $password_check){
        header("location:register.php?error=Password does not match");
        exit();
    }else{
        // $password = md5($password); #可以hash保證使用者資料安全
        $sql = "SELECT * FROM users WHERE username='$username' ";
        $result=mysqli_query($conn,$sql);
        if(mysqli_num_rows($result) > 0){
            header("location:register.php?error=The user name is Takened,Try another");
            exit();
        }else{
            $sql2 = "INSERT INTO users(username,password) VALUES('$username', '$password')";
            $result2=mysqli_query($conn,$sql2);
            if($result2){
                header("location:../index.html?success=Your account has been created successfully");
                exit();
            }else{
                header("location:register.php?error=Unknown error occured&$user_data");
                exit();
            }
        }
    }
}



?>