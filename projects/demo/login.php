<?php
ob_start();
session_start();
$conn=require_once "config.php";
@$username = $_POST['username'];
@$password = $_POST['password'];

if($_SERVER["REQUEST_METHOD"] == "POST"){
    $sql = "SELECT * FROM users WHERE username ='$username' ";
    $result=mysqli_query($conn,$sql);
    if(mysqli_num_rows($result)==1 && $password==mysqli_fetch_assoc($result)["password"]){
        $_SESSION["username"] = mysqli_fetch_assoc($result)["username"];
        $_SESSION["password"] = mysqli_fetch_assoc($result)["password"];

        header("location:../udata/school.html");
    }else{
            function_alert("帳號或密碼錯誤");
        }
}
    else{
        function_alert("Something wrong"); 
    }

    mysqli_close($link);

function function_alert($message) { 
    
    echo "<script>alert('$message');
     window.location.href='index.html';
    </script>"; 
    return false;
} 
?>
