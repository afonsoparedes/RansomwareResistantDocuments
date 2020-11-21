package server.domain.fileVersion;


import server.database.Connector;
import server.database.DatabaseObject;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

public class FileVersion implements DatabaseObject {

    //PK
    private String versionUid;
    private String fileUid;
    private String creator;
    private String path;
    private Date date;

    public FileVersion(String versionUid, String fileUid, String creator, String path, Date date) {
        this.versionUid = versionUid;
        this.fileUid = fileUid;
        this.creator = creator;
        this.path = path;
        this.date = date;
    }

    public FileVersion() {
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getVersionUid() {
        return versionUid;
    }

    public void setVersionUid(String versionUid) {
        this.versionUid = versionUid;
    }

    public String getFileUid() {
        return fileUid;
    }

    public void setFileUid(String fileUid) {
        this.fileUid = fileUid;
    }

    public String getUid() {
        return versionUid;
    }

    public void setUid(String uid) {
        this.versionUid = uid;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

    @Override
    public void saveInDatabase(Connector connector) {
        try {
            //Prepared statement
            String sql = "INSERT INTO FileVersions VALUES (?,?,?,?,?)";
            PreparedStatement s = connector.connection.prepareStatement(sql);

            //Set parameters
            s.setString(1,this.versionUid);
            s.setString(2,this.fileUid);
            s.setString(3,this.creator);
            s.setString(4,this.path);
            s.setTimestamp(5,new Timestamp(this.date.getTime()));
            s.executeUpdate();

            connector.connection.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            //Rollback changes in case of failure
            try {
                connector.connection.rollback();
            } catch (SQLException ignored) { }
        }
    }
}
