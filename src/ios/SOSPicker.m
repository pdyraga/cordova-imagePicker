//
//  SOSPicker.m
//  SyncOnSet
//
//  Created by Christopher Sullivan on 10/25/13.
//
//

#import "SOSPicker.h"
#import "ELCAlbumPickerController.h"
#import "ELCImagePickerController.h"
#import "ELCAssetTablePicker.h"

#define CDV_PHOTO_PREFIX @"cdv_photo_"
#define CDV_ORIGINAL_PHOTO_PREFIX @"cdv_original_photo_"

@implementation SOSPicker

@synthesize callbackId;

- (void) getPictures:(CDVInvokedUrlCommand *)command {
	NSDictionary *options = [command.arguments objectAtIndex: 0];

	NSInteger maximumImagesCount = [[options objectForKey:@"maximumImagesCount"] integerValue];
	self.width = [[options objectForKey:@"width"] integerValue];
	self.height = [[options objectForKey:@"height"] integerValue];
	self.quality = [[options objectForKey:@"quality"] integerValue];

	// Create the an album controller and image picker
	ELCAlbumPickerController *albumController = [[ELCAlbumPickerController alloc] init];
	
	if (maximumImagesCount == 1) {
      albumController.immediateReturn = true;
      albumController.singleSelection = true;
   } else {
      albumController.immediateReturn = false;
      albumController.singleSelection = false;
   }
   
   ELCImagePickerController *imagePicker = [[ELCImagePickerController alloc] initWithRootViewController:albumController];
   imagePicker.maximumImagesCount = maximumImagesCount;
   imagePicker.returnsOriginalImage = 1;
   imagePicker.imagePickerDelegate = self;

   albumController.parent = imagePicker;
	self.callbackId = command.callbackId;
	// Present modally
	[self.viewController presentViewController:imagePicker
	                       animated:YES
	                     completion:nil];
}


- (void)elcImagePickerController:(ELCImagePickerController *)picker didFinishPickingMediaWithInfo:(NSArray *)info {
	CDVPluginResult* result = nil;

    NSMutableArray *originalImages = [[NSMutableArray alloc] init];
    NSMutableArray *scaledImages = [[NSMutableArray alloc] init];
    NSMutableArray *scaleFactors = [[NSMutableArray alloc] init];

    NSData* data = nil;
    NSString* docsPath = [NSTemporaryDirectory()stringByStandardizingPath];
    NSError* err = nil;
    NSFileManager* fileMgr = [[NSFileManager alloc] init];
    NSString* originalFilePath;
    NSString* scaledFilePath;
    ALAsset* asset = nil;
    UIImageOrientation orientation = UIImageOrientationUp;;
    CGSize targetSize = CGSizeMake(self.width, self.height);
	for (NSDictionary *dict in info) {
        asset = [dict objectForKey:@"ALAsset"];
        // From ELCImagePickerController.m

        int i = 1;
        do {
            scaledFilePath = [NSString stringWithFormat:@"%@/%@%03d.%@", docsPath, CDV_PHOTO_PREFIX, i++, @"jpg"];
        } while ([fileMgr fileExistsAtPath:scaledFilePath]);

        int j = 1;
        do {
            originalFilePath = [NSString stringWithFormat:@"%@/%@%03d.%@", docsPath, CDV_ORIGINAL_PHOTO_PREFIX, j++, @"jpg"];
        } while ([fileMgr fileExistsAtPath:originalFilePath]);
        
        @autoreleasepool {
            ALAssetRepresentation *assetRep = [asset defaultRepresentation];
            CGImageRef imgRef = NULL;
            
            //defaultRepresentation returns image as it appears in photo picker, rotated and sized,
            //so use UIImageOrientationUp when creating our image below.
            if (picker.returnsOriginalImage) {
                imgRef = [assetRep fullResolutionImage];
                orientation = [assetRep orientation];
            } else {
                imgRef = [assetRep fullScreenImage];
            }
            
            //
            // copy selected image to tmp directory
            //
            Byte *buffer = (Byte*)malloc(assetRep.size);
            NSUInteger buffered = [assetRep getBytes:buffer fromOffset:0 length:assetRep.size error:nil];
            NSData *data = [NSData dataWithBytesNoCopy:buffer length:buffered freeWhenDone:YES];
            if (![data writeToFile:originalFilePath options:NSAtomicWrite error:&err]) {
                result = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsString:[err localizedDescription]];
                break;
            } else {               
                [originalImages addObject:[[NSURL fileURLWithPath:originalFilePath] absoluteString]];
            }

            //
            // scale selected image and save it in separate file in tmp directory
            //
            UIImage* image = [UIImage imageWithCGImage:imgRef scale:1.0f orientation:orientation];

            if (self.width == 0 && self.height == 0) {
                [scaleFactors addObject:[NSNumber numberWithFloat:1.0f]];
                data = UIImageJPEGRepresentation(image, self.quality/100.0f);
            } else {                
                CGFloat scaleFactor = [self computeScaleFactor:image toSize:targetSize];
                [scaleFactors addObject:[NSNumber numberWithFloat:scaleFactor]];
                UIImage* scaledImage = [self imageByScalingNotCroppingForSize:image toSize:targetSize scaleFactor:scaleFactor];
                data = UIImageJPEGRepresentation(scaledImage, self.quality/100.0f);
            }
            
            if (![data writeToFile:scaledFilePath options:NSAtomicWrite error:&err]) {
                result = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsString:[err localizedDescription]];
                break;
            } else {
                [scaledImages addObject:[[NSURL fileURLWithPath:scaledFilePath] absoluteString]];
            }
        }

	}
	
	if (nil == result) {
       NSMutableArray *images = [[NSMutableArray alloc] init];         
       NSUInteger index = 0;

       for (NSURL *scaledImage in scaledImages) {
           NSDictionary *jsonObj = @{
               @"previewImageUri" : scaledImage,
               @"originalImageUri" : originalImages[index],
               @"previewScaleFactor" : scaleFactors[index]
           };
           [images addObject:jsonObj];

           index++;
       }

		result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:images];
	}

	[self.viewController dismissViewControllerAnimated:YES completion:nil];
	[self.commandDelegate sendPluginResult:result callbackId:self.callbackId];
}

- (void)elcImagePickerControllerDidCancel:(ELCImagePickerController *)picker {
	[self.viewController dismissViewControllerAnimated:YES completion:nil];
	CDVPluginResult* pluginResult = nil;
    NSArray* emptyArray = [NSArray array];
	pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:emptyArray];
	[self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackId];
}

- (CGFloat)computeScaleFactor:(UIImage*)anImage toSize:(CGSize)frameSize
{
    CGSize imageSize = anImage.size;
    CGFloat width = imageSize.width;
    CGFloat height = imageSize.height;
    CGFloat targetWidth = frameSize.width;
    CGFloat targetHeight = frameSize.height;
    CGFloat scaleFactor = 1.0;

    if (CGSizeEqualToSize(imageSize, frameSize) == NO) {
        CGFloat widthFactor = targetWidth / width;
        CGFloat heightFactor = targetHeight / height;

        // opposite comparison to imageByScalingAndCroppingForSize in order to contain the image within the given bounds
        if (widthFactor == 0.0) {
            scaleFactor = heightFactor;
        } else if (heightFactor == 0.0) {
            scaleFactor = widthFactor;
        } else if (widthFactor > heightFactor) {
            scaleFactor = heightFactor; // scale to fit height
        } else {
            scaleFactor = widthFactor; // scale to fit width
        }        
    }

    return scaleFactor;   
}

- (UIImage*)imageByScalingNotCroppingForSize:(UIImage*)anImage toSize:(CGSize)frameSize scaleFactor:(CGFloat)scaleFactor
{
    UIImage* sourceImage = anImage;
    UIImage* newImage = nil;

    CGSize imageSize = sourceImage.size;
    CGFloat width = imageSize.width;
    CGFloat height = imageSize.height;

    CGSize scaledSize = frameSize;

    if (scaleFactor != 1.0) {
      scaledSize = CGSizeMake(width * scaleFactor, height * scaleFactor);
    }

    UIGraphicsBeginImageContext(scaledSize); // this will resize

    [sourceImage drawInRect:CGRectMake(0, 0, scaledSize.width, scaledSize.height)];

    newImage = UIGraphicsGetImageFromCurrentImageContext();
    if (newImage == nil) {
        NSLog(@"could not scale image");
    }

    // pop the context to get back to the default
    UIGraphicsEndImageContext();
    return newImage;
}

@end
