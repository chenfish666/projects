<?php
session_start();
$conn=require_once "../demo/config.php";
if (isset($_POST['name']) && isset($_POST['position']) && isset($_POST['birthdate']) && isset($_POST['graduation_year']) && isset($_POST['gmail'])){
    @$name = $_POST['name'];
    @$position = $_POST['position'];
    @$birthdate = $_POST['birthdate'];
    @$graduation_year = $_POST['graduation_year'];
    @$gmail = $_POST['gmail'];

    $sql = "SELECT * FROM udata WHERE name='$name' ";
        $result=mysqli_query($conn,$sql);
        if(mysqli_num_rows($result) > 0){
            header("location:school.html?error=The name is Takened,Try another");
            exit();
        }else{
            $sql2 = "INSERT INTO udata(name,position,birthdate,graduation_year,gmail) VALUES('$name', '$position','$birthdate','$graduation_year','$gmail')";
            $result2=mysqli_query($conn,$sql2);
            if($result2){
                header("location:school.html?success=Your Data has been created successfully");
                exit();
            }else{
                header("location:school.html?error=Unknown error occured&$user_data");
                exit();
            }
        }

}
// 執行資料庫查詢，取得資料
$sql = "SELECT * FROM udata";
$result3 = $conn->query($sql);

$data = array(); // 建立一個空陣列用於存儲所有資料

if ($result3->num_rows > 0) {
    while ($row = $result3->fetch_assoc()) {
        $data[] = array(
            'name' => $row["name"],
            'position' => $row["position"],
            'gmail' => $row["gmail"],
            'birthdate' => $row["birthdate"],
            'graduation_year' => $row["graduation_year"]
        );
    }
    echo json_encode($data); // 將資料以 JSON 格式返回給前端
} else {
    echo json_encode(array('error' => 'No data found')); // 若無資料則返回錯誤訊息
}
$conn->close();
?>